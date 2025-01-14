/**
 * Copyright 2015 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.swarm.container;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.log.StreamModuleLogger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Domain;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.impl.base.exporter.zip.ZipExporterImpl;
import org.jboss.shrinkwrap.impl.base.spec.JavaArchiveImpl;
import org.jboss.shrinkwrap.impl.base.spec.WebArchiveImpl;
import org.wildfly.swarm.bootstrap.modules.BootModuleLoader;

/**
 * A WildFly-Swarm container.
 *
 * @author Bob McWhirter
 * @author Ken Finnigan
 */
public class Container {

    public static final String VERSION;

    static {
        InputStream in = Container.class.getClassLoader().getResourceAsStream("wildfly-swarm.properties");
        Properties props = new Properties();
        try {
            props.load(in);
        } catch (IOException e) {
            e.printStackTrace();
        }

        VERSION = props.getProperty("version", "unknown");
    }

    private Map<Class<? extends Fraction>, Fraction> fractions = new ConcurrentHashMap<>();
    private Map<String, Fraction> fractionsBySimpleName = new ConcurrentHashMap<>();

    private List<Fraction> dependentFractions = new ArrayList<>();

    private Set<Class<? extends Fraction>> defaultFractionTypes = new HashSet<>();

    private List<SocketBindingGroup> socketBindingGroups = new ArrayList<>();

    private Map<String, List<SocketBinding>> socketBindings = new HashMap<>();

    private List<Interface> interfaces = new ArrayList<>();

    private Server server;

    private Deployer deployer;

    private Domain domain;

    /**
     * Command line args if any
     */
    private String[] args;

    /**
     * Construct a new, un-started container.
     *
     * @throws Exception If an error occurs performing classloading and initialization magic.
     */
    public Container() throws Exception {
        this(false);
    }

    /**
     * Construct a new, un-started container.
     *
     * @param debugBootstrap - flag to indicate if the module layer should be put into bootstrap debug mode. Same as
     *                       the jboss-module -debuglog mode which enables trace logging to System.out during the
     *                       initial bootstrap of the module layer.
     * @throws Exception If an error occurs performing classloading and initialization magic.
     */
    public Container(boolean debugBootstrap) throws Exception {
        System.setProperty("wildfly.swarm.version", VERSION);
        createServer(debugBootstrap);
        createShrinkWrapDomain();
    }

    private void createShrinkWrapDomain() throws ModuleLoadException {
        ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        try {
            if (isFatJar()) {
                Thread.currentThread().setContextClassLoader(Container.class.getClassLoader());
                Module appModule = Module.getBootModuleLoader().loadModule(ModuleIdentifier.create("swarm.application"));
                Thread.currentThread().setContextClassLoader(appModule.getClassLoader());
            }
            this.domain = ShrinkWrap.getDefaultDomain();
            this.domain.getConfiguration().getExtensionLoader().addOverride(ZipExporter.class, ZipExporterImpl.class);
            this.domain.getConfiguration().getExtensionLoader().addOverride(JavaArchive.class, JavaArchiveImpl.class);
            this.domain.getConfiguration().getExtensionLoader().addOverride(WebArchive.class, WebArchiveImpl.class);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }
    }

    private void createServer(boolean debugBootstrap) throws Exception {
        if (System.getProperty("boot.module.loader") == null) {
            System.setProperty("boot.module.loader", BootModuleLoader.class.getName());
        }
        if (debugBootstrap) {
            Module.setModuleLogger(new StreamModuleLogger(System.err));
        }
        Module module = Module.getBootModuleLoader().loadModule(ModuleIdentifier.create("org.wildfly.swarm.container", "runtime"));
        Class<?> serverClass = module.getClassLoader().loadClass("org.wildfly.swarm.container.runtime.RuntimeServer");
        try {
            this.server = (Server) serverClass.newInstance();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void applyFractionDefaults(Server server) throws Exception {
        Set<Class<? extends Fraction>> availFractions = server.getFractionTypes();

        // Process any dependent fractions from Application added fractions
        if (!this.dependentFractions.isEmpty()) {
            this.dependentFractions.stream().filter(dependentFraction -> this.fractions.get(dependentFraction.getClass()) == null).forEach(this::fraction);
            this.dependentFractions.clear();
        }

        // Provide defaults for those remaining
        availFractions.stream().filter(fractionClass -> this.fractions.get(fractionClass) == null).forEach(fractionClass -> fractionDefault(server.createDefaultFor(fractionClass)));

        // Determine if any dependent fractions should override non Application added fractions
        if (!this.dependentFractions.isEmpty()) {
            this.dependentFractions.stream().filter(dependentFraction -> this.fractions.get(dependentFraction.getClass()) == null || (this.fractions.get(dependentFraction.getClass()) != null && this.defaultFractionTypes.contains(dependentFraction.getClass()))).forEach(this::fraction);
            this.dependentFractions.clear();
        }
    }

    /**
     * Add a fraction to the container.
     *
     * @param fraction The fraction to add.
     * @return The container.
     */
    public Container fraction(Fraction fraction) {
        if ( fraction != null ) {
            this.fractions.put(fractionRoot(fraction.getClass()), fraction);
            this.fractionsBySimpleName.put(fraction.simpleName(), fraction);
            fraction.initialize(new InitContext());
        }
        return this;
    }

    public Container fraction(Supplier<Fraction> supplier) {
        return fraction( supplier.get() );
    }

    public List<Fraction> fractions() {
        return this.fractions.values().stream().collect(Collectors.toList());
    }

    private void fractionDefault(Fraction defaultFraction) {
        if (defaultFraction == null) {
            return;
        }
        this.defaultFractionTypes.add(fractionRoot(defaultFraction.getClass()));
        fraction(defaultFraction);
    }

    private Class<? extends Fraction> fractionRoot(Class<? extends Fraction> fractionClass) {
        Class<? extends Fraction> fractionRoot = fractionClass;
        boolean rootFound = false;

        while (!rootFound) {
            Class<?>[] interfaces = fractionRoot.getInterfaces();
            for (Class<?> anInterface : interfaces) {
                if (anInterface.getName().equals(Fraction.class.getName())) {
                    rootFound = true;
                    break;
                }
            }

            if (!rootFound) {
                fractionRoot = (Class<? extends Fraction>) fractionRoot.getSuperclass();
            }
        }

        return fractionRoot;
    }

    /**
     * Add a fraction to the container that is a dependency of another fraction.
     *
     * @param fraction The dependent fraction to add.
     */
    private void dependentFraction(Fraction fraction) {
        this.dependentFractions.add(fraction);
    }

    /**
     * Configure a network interface.
     *
     * @param name       The name of the interface.
     * @param expression The expression to define the interface.
     * @return The container.
     */
    public Container iface(String name, String expression) {
        this.interfaces.add(new Interface(name, expression));
        return this;
    }

    public List<Interface> ifaces() {
        return this.interfaces;
    }

    /**
     * Configure a socket-binding-group.
     *
     * @param group The socket-binding group to add.
     * @return The container.
     */
    public Container socketBindingGroup(SocketBindingGroup group) {
        this.socketBindingGroups.add(group);
        return this;
    }

    public List<SocketBindingGroup> socketBindingGroups() {
        return this.socketBindingGroups;
    }

    public SocketBindingGroup getSocketBindingGroup(String name) {
        for (SocketBindingGroup each : this.socketBindingGroups) {
            if (each.name().equals(name)) {
                return each;
            }
        }

        return null;
    }


    public Map<String, List<SocketBinding>> socketBindings() {
        return this.socketBindings;
    }


    void socketBinding(SocketBinding binding) {
        socketBinding("default-sockets", binding);
    }

    void socketBinding(String groupName, SocketBinding binding) {
        List<SocketBinding> list = this.socketBindings.get(groupName);

        if (list == null) {
            list = new ArrayList<>();
            this.socketBindings.put(groupName, list);
        }

        for (SocketBinding each : list) {
            if (each.name().equals(binding.name())) {
                throw new RuntimeException("Socket binding '" + binding.name() + "' already configured for '" + each.portExpression() + "'");
            }
        }

        list.add(binding);
    }

    /**
     * Start the container.
     *
     * @return The container.
     * @throws Exception if an error occurs.
     */
    public Container start() throws Exception {
        this.deployer = this.server.start(this);
        return this;
    }

    /**
     * Stop the container, undeploying all deployments.
     *
     * @return THe container.
     * @throws Exception If an error occurs.
     */
    public Container stop() throws Exception {
        this.server.stop();
        return this;
    }

    /**
     * Start the container with a deployment.
     * <p/>
     * <p>Effectively calls {@code start().deploy(deployment)}</p>
     *
     * @param deployment The deployment to deploy.
     * @return The container.
     * @throws Exception if an error occurs.
     * @see #start()
     * @see #deploy(Archive)
     */
    public Container start(Archive deployment) throws Exception {
        return start().deploy(deployment);
    }

    /**
     * Deploy the default WAR deployment.
     * <p/>
     * <p>For WAR-based applications, the primary WAR artifact iwll be deployed.</p>
     *
     * @return The container.
     * @throws Exception if an error occurs.
     */
    public Container deploy() throws Exception {
        Archive deployment = createDefaultDeployment();
        if (deployment == null) {
            System.err.println("[WARN] Unable to create default deployment");
            return this;
        } else {
            return deploy(deployment);
        }
    }

    /**
     * Deploy an archive.
     *
     * @param deployment The ShrinkWrap archive to deploy.
     * @return The container.
     * @throws Exception if an error occurs.
     */
    public Container deploy(Archive deployment) throws Exception {
        this.deployer.deploy(deployment);
        return this;
    }

    /**
     * Get the possibly null container main method arguments.
     *
     * @return main method arguments, possibly null
     */
    public String[] getArgs() {
        return args;
    }

    /**
     * Set the main method arguments. This will be available as a ValueService<String[]> under the name
     * wildfly.swarm.main-args
     *
     * @param args arguments passed to the main(String[]) method.
     */
    public void setArgs(String[] args) {
        this.args = args;
    }

    /**
     * Initialization Context to be passed to Fractions to allow them to provide
     * additional functionality into the Container.
     */
    public class InitContext {
        public void fraction(Fraction fraction) {
            Container.this.dependentFraction(fraction);
        }

        public void socketBinding(SocketBinding binding) {
            socketBinding("default-sockets", binding);
        }

        public void socketBinding(String groupName, SocketBinding binding) {
            Container.this.socketBinding(groupName, binding);
        }
    }

    public class PostInitContext extends InitContext {
        public boolean hasFraction(String simpleName) {
            return fractions().stream().anyMatch((f) -> f.simpleName().equalsIgnoreCase(simpleName));
        }

        public Fraction fraction(String simpleName) {
            System.err.println( simpleName + " vs " + fractions() );
            Optional<Fraction> opt = fractions().stream().filter((f) -> f.simpleName().equalsIgnoreCase(simpleName)).findFirst();
            return opt.orElse(null);
        }
    }

    public PostInitContext createPostInitContext() {
        return new PostInitContext();
    }


    protected Archive createDefaultDeployment() throws Exception {
        Module m1 = Module.getBootModuleLoader().loadModule(ModuleIdentifier.create("swarm.application"));
        ServiceLoader<DefaultDeploymentFactory> providerLoader = m1.loadService(DefaultDeploymentFactory.class);

        Iterator<DefaultDeploymentFactory> providerIter = providerLoader.iterator();

        if (!providerIter.hasNext()) {
            providerLoader = ServiceLoader.load(DefaultDeploymentFactory.class, ClassLoader.getSystemClassLoader());
            providerIter = providerLoader.iterator();
        }

        Map<String, DefaultDeploymentFactory> factories = new HashMap<>();

        while (providerIter.hasNext()) {
            DefaultDeploymentFactory factory = providerIter.next();
            DefaultDeploymentFactory current = factories.get(factory.getType());
            if (current == null) {
                factories.put(factory.getType(), factory);
            } else {
                // if this one is high priority than the previously-seen
                // factory, replace it.
                if (factory.getPriority() > current.getPriority()) {
                    factories.put(factory.getType(), factory);
                }
            }
        }

        String type = determineDeploymentType();
        DefaultDeploymentFactory factory = factories.get(type);

        if (factory != null) {
            return factory.create(this);
        }

        return null;
    }

    protected String determineDeploymentType() throws IOException {
        String artifact = System.getProperty("wildfly.swarm.app.path");
        if (artifact != null) {
            int dotLoc = artifact.lastIndexOf('.');
            if (dotLoc >= 0) {
                return artifact.substring(dotLoc + 1);
            }
        }

        artifact = System.getProperty("wildfly.swarm.app.artifact");
        if (artifact != null) {
            int dotLoc = artifact.lastIndexOf('.');
            if (dotLoc >= 0) {
                return artifact.substring(dotLoc + 1);
            }
        }

        if (Files.exists(Paths.get("pom.xml"))) {
            try (BufferedReader in = new BufferedReader(new FileReader(Paths.get("pom.xml").toFile()))) {
                String line = null;

                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.equals("<packaging>jar</packaging>")) {
                        return "jar";
                    } else if (line.equals("<packaging>war</packaging>")) {
                        return "war";
                    }
                }
            }
        }

        if (Files.exists(Paths.get("Mavenfile"))) {
            try (BufferedReader in = new BufferedReader(new FileReader(Paths.get("Mavenfile").toFile()))) {
                String line = null;

                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.equals("packaging :jar")) {
                        return "jar";
                    } else if (line.equals("packaging :war")) {
                        return "war";
                    }
                }
            }
        }

        // when in doubt, assume at least a .jar
        return "jar";
    }

    public static boolean isFatJar() throws IOException {
        URL location = Container.class.getProtectionDomain().getCodeSource().getLocation();
        Path root = null;
        if (location.getProtocol().equals("file")) {
            try {
                root = Paths.get(location.toURI());
            } catch (URISyntaxException e) {
                throw new IOException(e);
            }
        } else if ( location.toExternalForm().startsWith( "jar:file:" ) ) {
            return true;
        }

        if (Files.isRegularFile(root)) {
            try (JarFile jar = new JarFile(root.toFile())) {
                ZipEntry propsEntry = jar.getEntry("META-INF/wildfly-swarm.properties");
                if (propsEntry != null) {
                    try (InputStream in = jar.getInputStream(propsEntry)) {
                        Properties props = new Properties();
                        props.load(in);
                        if (props.containsKey("wildfly.swarm.app.artifact")) {
                            System.setProperty("wildfly.swarm.app.artifact", props.getProperty("wildfly.swarm.app.artifact"));
                        }

                        Enumeration<String> names = (Enumeration<String>) props.propertyNames();
                        while (names.hasMoreElements()) {
                            String name = names.nextElement();
                            String value = props.getProperty(name);
                            if (System.getProperty(name) == null) {
                                System.setProperty(name, value);
                            }
                        }
                    }
                    return true;
                }
            }
        }

        return false;
    }
}
