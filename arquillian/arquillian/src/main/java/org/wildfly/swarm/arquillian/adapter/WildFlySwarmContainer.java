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
package org.wildfly.swarm.arquillian.adapter;

import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;
import org.wildfly.swarm.arquillian.daemon.container.DaemonContainerConfigurationBase;
import org.wildfly.swarm.arquillian.daemon.container.DaemonDeployableContainerBase;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.jboss.shrinkwrap.resolver.api.maven.ConfigurableMavenResolverSystem;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenChecksumPolicy;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenRemoteRepositories;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenRemoteRepository;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenUpdatePolicy;
import org.wildfly.swarm.arquillian.daemon.DaemonServiceActivator;
import org.wildfly.swarm.container.JARArchive;
import org.wildfly.swarm.msc.ServiceActivatorArchive;
import org.wildfly.swarm.tools.BuildTool;
import org.wildfly.swarm.tools.exec.SwarmExecutor;
import org.wildfly.swarm.tools.exec.SwarmProcess;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * @author Bob McWhirter
 * @author Toby Crawley
 */
public class WildFlySwarmContainer extends DaemonDeployableContainerBase<DaemonContainerConfigurationBase> {

    private Class<?> testClass;

    private Set<String> requestedMavenArtifacts;

    private SwarmProcess process;


    @Override
    public Class<DaemonContainerConfigurationBase> getConfigurationClass() {
        return DaemonContainerConfigurationBase.class;
    }

    @Override
    public void start() throws LifecycleException {
        //disable start, since we call super.start() at deploy time
    }

    public void setTestClass(Class<?> testClass) {
        this.testClass = testClass;
    }

    public void setRequestedMavenArtifacts(List<String> artifacts) {
        this.requestedMavenArtifacts = new HashSet<>(artifacts);
    }

    public boolean isContainerFactory(Class<?> cls) {
        if (cls.getName().equals("org.wildfly.swarm.ContainerFactory")) {
            return true;
        }

        for (Class<?> interf : cls.getInterfaces()) {
            if (isContainerFactory(interf)) {
                return true;
            }
        }

        if (cls.getSuperclass() != null) {
            return isContainerFactory(cls.getSuperclass());
        }

        return false;
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {

        /*
        System.err.println( ">>> CORE" );
        System.err.println(" NAME: " + archive.getName());
        for (Map.Entry<ArchivePath, Node> each : archive.getContent().entrySet()) {
            System.err.println("-> " + each.getKey());
        }
        System.err.println( "<<< CORE" );
        */

        //System.err.println("is factory: " + isContainerFactory(this.testClass));
        if (isContainerFactory(this.testClass)) {
            archive.as(JavaArchive.class).addAsServiceProvider("org.wildfly.swarm.ContainerFactory", this.testClass.getName());
            archive.as(JavaArchive.class).addClass(this.testClass);
        }
        archive.as(ServiceActivatorArchive.class)
                .addServiceActivator(DaemonServiceActivator.class);
        archive.as(JARArchive.class)
                .addModule("org.wildfly.swarm.arquillian.daemon")
                .addModule("org.jboss.msc");

        BuildTool tool = new BuildTool();
        tool.projectArchive(archive);

        MavenRemoteRepository jbossPublic = MavenRemoteRepositories.createRemoteRepository("jboss-public-repository-group", "http://repository.jboss.org/nexus/content/groups/public/", "default");
        jbossPublic.setChecksumPolicy(MavenChecksumPolicy.CHECKSUM_POLICY_IGNORE);
        jbossPublic.setUpdatePolicy(MavenUpdatePolicy.UPDATE_POLICY_NEVER);

        ConfigurableMavenResolverSystem resolver = Maven.configureResolver()
                .withMavenCentralRepo(true)
                .withRemoteRepo(jbossPublic);

        tool.artifactResolvingHelper(new ShrinkwrapArtifactResolvingHelper(resolver));

        boolean hasRequestedArtifacts = this.requestedMavenArtifacts != null && this.requestedMavenArtifacts.size() > 0;

        if (!hasRequestedArtifacts) {
            MavenResolvedArtifact[] deps = resolver.loadPomFromFile("pom.xml").importRuntimeAndTestDependencies().resolve().withTransitivity().asResolvedArtifact();

            for (MavenResolvedArtifact dep : deps) {
                MavenCoordinate coord = dep.getCoordinate();
                tool.dependency(dep.getScope().name(), coord.getGroupId(), coord.getArtifactId(), coord.getVersion(), coord.getPackaging().getExtension(), coord.getClassifier(), dep.asFile());
            }
        } else {
            // ensure that arq daemon is available
            this.requestedMavenArtifacts.add("org.wildfly.swarm:wildfly-swarm-arquillian-daemon");
            for (String requestedDep : this.requestedMavenArtifacts) {
                MavenResolvedArtifact[] deps = resolver.loadPomFromFile("pom.xml").resolve(requestedDep).withTransitivity().asResolvedArtifact();

                for (MavenResolvedArtifact dep : deps) {
                    MavenCoordinate coord = dep.getCoordinate();
                    tool.dependency(dep.getScope().name(), coord.getGroupId(), coord.getArtifactId(), coord.getVersion(), coord.getPackaging().getExtension(), coord.getClassifier(), dep.asFile());
                }
            }
        }

        SwarmExecutor executor = new SwarmExecutor();
        executor.withDefaultSystemProperties();//.withDebug(8787);

        try {

            Archive<?> wrapped = tool.build();


            /*wrapped.as(ZipExporter.class).exportTo(new File("test.jar"), true);

            for (Map.Entry<ArchivePath, Node> each : wrapped.getContent().entrySet()) {
                System.err.println("-> " + each.getKey());
            }*/

            File executable = File.createTempFile("arquillian", "-swarm.jar");
            wrapped.as(ZipExporter.class).exportTo(executable, true);
            executable.deleteOnExit();


            executor.withProperty( "java.net.preferIPv4Stack", "true" );
            executor.withExecutableJar( executable.toPath() );


            File workingDirectory = Files.createTempDirectory("arquillian").toFile();
            workingDirectory.deleteOnExit();
            executor.withWorkingDirectory( workingDirectory.toPath() );

            this.process = executor.execute();
            this.process.getOutputStream().close();

            this.process.awaitDeploy( 2, TimeUnit.MINUTES );

            if ( ! this.process.isAlive() ) {
                throw new DeploymentException( "Process failed to start" );
            }
            if ( this.process.getError() != null ) {
                throw new DeploymentException( "Error starting process", this.process.getError() );
            }

            // start wants to connect to the remote container, which isn't up until now, so
            // we override start above and call it here instead
            super.start();

            ProtocolMetaData metaData = new ProtocolMetaData();
            metaData.addContext(createDeploymentContext(archive.getId()));

            return metaData;
        } catch (Exception e) {
            throw new DeploymentException(e.getMessage(), e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        try {
            this.process.stop();
        } catch (InterruptedException e) {
            throw new DeploymentException( "Unable to stop process", e );
        }
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
    }
}
