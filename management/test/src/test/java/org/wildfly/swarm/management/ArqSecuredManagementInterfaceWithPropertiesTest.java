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
package org.wildfly.swarm.management;

import java.util.Properties;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.swarm.ContainerFactory;
import org.wildfly.swarm.container.Container;
import org.wildfly.swarm.container.JARArchive;

import static org.fest.assertions.Assertions.assertThat;

/**
 * @author Bob McWhirter
 */
@RunWith(Arquillian.class)
public class ArqSecuredManagementInterfaceWithPropertiesTest implements ContainerFactory {

    @Deployment(testable = false)
    public static Archive createDeployment() {
        JARArchive deployment = ShrinkWrap.create(JARArchive.class, "myapp.jar");
        deployment.add(EmptyAsset.INSTANCE, "nothing");
        return deployment;
    }

    @Override
    public Container newContainer(String... args) throws Exception {
        return new Container()
                .fraction(
                        ManagementFraction.createDefaultFraction()
                                .httpInterfaceManagementInterface((iface) -> {
                                    iface.securityRealm("ManagementRealm");
                                })
                                .securityRealm("ManagementRealm", (realm) -> {
                                    realm.inMemoryAuthentication( (authn)->{
                                        authn.add( new Properties() {{
                                            put( "bob", "tacos!" );
                                        }}, true );
                                    });
                                    realm.inMemoryAuthorization();
                                })
                );
    }

    @Test
    @RunAsClient
    public void testClient() throws Exception {

        ModelControllerClient client = ModelControllerClient.Factory.create(
                "localhost", 9990, new AuthCallbackHandler("bob", "tacos!")
        );

        ModelNode response = client.execute(Operations.createOperation("whoami"));

        assertThat(response.get("outcome").asString()).isEqualTo("success");

        ModelNode result = response.get("result");

        assertThat(result).isNotNull();
        assertThat(result.isDefined()).isTrue();

        ModelNode identity = result.get("identity");

        assertThat(identity).isNotNull();
        assertThat(identity.isDefined()).isTrue();

        assertThat(identity.get("username").asString()).isEqualTo("bob");

        // ===

        response = client.execute(Operations.createOperation("read-resource", PathAddress.pathAddress(PathElement.pathElement("deployment", "*")).toModelNode()));

        assertThat(response.get("outcome").asString()).isEqualTo("success");

        result = response.get("result");

        assertThat(result).isNotNull();
        assertThat(result.isDefined()).isTrue();
        assertThat(result.getType()).isEqualTo(ModelType.LIST);
        assertThat(result.asList()).hasSize(1);

        ModelNode myapp = result.get(0);

        assertThat(myapp).isNotNull();
        assertThat(myapp.isDefined()).isTrue();

        ModelNode myappResult = myapp.get("result");

        assertThat(myappResult).isNotNull();
        assertThat(myappResult.isDefined()).isTrue();

        assertThat(myappResult.get("name").asString()).isEqualTo("myapp.jar");

    }

}
