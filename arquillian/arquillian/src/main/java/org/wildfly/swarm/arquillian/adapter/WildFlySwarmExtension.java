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

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.test.spi.client.protocol.Protocol;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.wildfly.swarm.arquillian.daemon.protocol.DaemonProtocol;

/**
 * @author Bob McWhirter
 */
public class WildFlySwarmExtension implements LoadableExtension {
    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(Protocol.class, DaemonProtocol.class)
                .service(DeployableContainer.class, WildFlySwarmContainer.class)
                .observer(WildFlySwarmObserver.class);
    }
}
