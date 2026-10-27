package com.lssoluciones.lscore.platform;

import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import java.util.Arrays;
import org.springframework.core.env.StandardEnvironment;

/** Explicit terminal installation, never starts Spring or exposes an HTTP/admin mutation endpoint. */
public final class PlatformOwnerBootstrapCommand {
    private PlatformOwnerBootstrapCommand() { }

    public static void main(String[] args) {
        char[] password = null;
        int exitCode = 0;
        try {
            var console = System.console();
            if (console == null || args.length != 1 || !"--initialize-owner".equals(args[0])
                    || !"INITIALIZE LS SOLUCIONES OWNER".equals(console.readLine("Type INITIALIZE LS SOLUCIONES OWNER: "))) {
                throw new IllegalArgumentException();
            }
            var environment = new StandardEnvironment();
            var authentication = new JdbcPasswordAuthentication(PlatformDatabase.source(environment,
                    "LS_AUTHENTICATION_DB_", JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            String username = console.readLine("Existing definitive account username: ");
            password = console.readPassword("Existing definitive account password: ");
            var bootstrap = new JdbcPlatformBootstrap(PlatformDatabase.source(environment,
                    "LS_PLATFORM_BOOTSTRAP_DB_", JdbcPlatformBootstrap.ROLE), authentication);
            bootstrap.initialize(username, password);
            console.printf("Owner initialized. Close this process and seal the installation LOGIN.%n");
        } catch (RuntimeException failure) {
            System.err.println("Platform bootstrap unavailable");
            exitCode = 1;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
