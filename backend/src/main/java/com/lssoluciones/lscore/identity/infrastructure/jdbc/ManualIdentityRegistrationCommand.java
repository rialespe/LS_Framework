package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import java.io.Console;
import java.util.Arrays;
import java.util.Map;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Explicit local administrative entry point; does not start Spring, HTTP or business authorization.
 * After V10: administrator provisions the dedicated LOGIN, restricted migrator applies V11.
 * Supply LS_REGISTRATION_DB_HOST/PORT/NAME/PASSWORD separately; no fallback or selectable role.
 * Run the packaged backend with java -Dloader.main= followed by this fully qualified class name,
 * -cp backend/target/ls-core-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher --register.
 * A real terminal is required. Identity fields are read without echo and never passed in argv or logged.
 * Possession of the dedicated database credential must be restricted to authorized operators.
 */
public final class ManualIdentityRegistrationCommand {
    private ManualIdentityRegistrationCommand() {
    }

    public static void main(String[] args) {
        try {
            Console console = System.console();
            if (args.length != 1 || !"--register".equals(args[0]) || console == null) {
                throw new IllegalArgumentException();
            }
            var service = service(System.getenv());
            String username = read(console, "Username: ");
            String name = read(console, "Display name: ");
            String email = read(console, "Email (empty to omit): ");
            service.register(new ManualIdentityInput(username, name, email.isEmpty() ? null : email));
            console.printf("Identity registered.%n");
        } catch (RuntimeException failure) {
            System.err.println("Identity registration unavailable");
            System.exit(1);
        }
    }

    static JdbcManualIdentityRegistration service(Map<String, String> settings) {
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[] {required(settings, "LS_REGISTRATION_DB_HOST")});
        int port = Integer.parseInt(required(settings, "LS_REGISTRATION_DB_PORT"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid registration configuration");
        }
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(required(settings, "LS_REGISTRATION_DB_NAME"));
        source.setUser(JdbcManualIdentityRegistration.DATABASE_ROLE);
        source.setPassword(required(settings, "LS_REGISTRATION_DB_PASSWORD"));
        source.setApplicationName("ls-core-identity-registration");
        source.setConnectTimeout(3);
        source.setSocketTimeout(5);
        return new JdbcManualIdentityRegistration(source);
    }

    private static String required(Map<String, String> settings, String name) {
        String value = settings.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invalid registration configuration");
        }
        return value;
    }

    private static String read(Console console, String prompt) {
        char[] value = console.readPassword("%s", prompt);
        if (value == null) {
            throw new IllegalArgumentException();
        }
        try {
            return new String(value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }
}
