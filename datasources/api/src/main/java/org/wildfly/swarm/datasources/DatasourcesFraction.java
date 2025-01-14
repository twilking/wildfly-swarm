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
package org.wildfly.swarm.datasources;

import org.wildfly.swarm.config.Datasources;
import org.wildfly.swarm.config.datasources.DataSource;
import org.wildfly.swarm.config.datasources.JDBCDriverConsumer;
import org.wildfly.swarm.container.Fraction;

/**
 * @author Bob McWhirter
 */
public class DatasourcesFraction extends Datasources<DatasourcesFraction> implements Fraction {
    @Override
    public DatasourcesFraction dataSource(DataSource value) {
        if (value.jndiName() == null) {
            value.jndiName("java:jboss/datasources/" + value.getKey());
        }
        return super.dataSource(value);
    }

    public DatasourcesFraction jdbcDriver(String childKey, JDBCDriverConsumer consumer) {
        return super.jdbcDriver( childKey, (driver)->{
            driver.driverName( childKey );
            if ( consumer != null ) {
                consumer.accept(driver);
            }
        });
    }

}
