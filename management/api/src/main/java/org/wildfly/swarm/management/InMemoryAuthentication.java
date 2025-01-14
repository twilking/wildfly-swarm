package org.wildfly.swarm.management;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Properties;

import org.wildfly.swarm.config.management.security_realm.PlugInAuthentication;

/**
 * @author Bob McWhirter
 */
public class InMemoryAuthentication {

    @FunctionalInterface
    public interface Consumer {
        void accept(InMemoryAuthentication authn);
    }

    private final String realm;

    private final PlugInAuthentication plugin;

    public InMemoryAuthentication(String realm, PlugInAuthentication plugin) {
        this.realm = realm;
        this.plugin = plugin;
    }

    public void add(Properties props) {
        add( props, false );
    }

    public void add(Properties props, boolean plainText) {
        Enumeration<?> userNames = props.propertyNames();

        while ( userNames.hasMoreElements() ) {
            String userName = (String) userNames.nextElement();
            String value = props.getProperty( userName );

            add( userName, value, plainText );
        }
    }

    public void add(String userName, String password) {
        add( userName, password, false );
    }

    public void add(String userName, String password, boolean plainText) {
        if ( plainText ) {
            try {
                String str = userName + ":" + this.realm + ":" + password;
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] hash = digest.digest(str.getBytes());
                add(userName, hash);
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        } else {
            this.plugin.property( userName + ".hash", (prop)-> {
                prop.value(password);
            });
        }
    }

    public void add(String userName, byte[] hash) {
        this.plugin.property( userName + ".hash", (prop)->{
            StringBuilder str = new StringBuilder();
            for (byte b : hash) {
                int i = b;
                String part = Integer.toHexString(b);
                if ( part.length() > 2 ) {
                    part = part.substring( part.length() - 2 );
                } else if ( part.length() < 2 ) {
                    part = "0" + part;
                }
                str.append( part );
            }

            prop.value( str.toString() );
        });
    }

}
