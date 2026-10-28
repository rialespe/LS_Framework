package com.lssoluciones.lscore.platform;

import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.core.env.StandardEnvironment;

/** One operation per console process. Target UUID is only a selector, never the actor identity. */
public final class PlatformPrivilegedCommand {
    private PlatformPrivilegedCommand() { }

    public static void main(String[] args) {
        char[] password = null;
        int exit = 1;
        try {
            var console = System.console();
            if (console == null || args.length != 2) {
                throw new IllegalArgumentException();
            }
            var action = switch (args[0]) {
                case "--designate-superadmin" -> JdbcPlatformAuthorization.Action.DESIGNATE_SUPERADMIN;
                case "--revoke-superadmin" -> JdbcPlatformAuthorization.Action.REVOKE_SUPERADMIN;
                default -> throw new IllegalArgumentException();
            };
            UUID target = UUID.fromString(args[1]);
            var environment = new StandardEnvironment();
            var authentication = new JdbcPasswordAuthentication(PlatformDatabase.source(environment,
                    "LS_AUTHENTICATION_DB_", JdbcPasswordAuthentication.ROLE), JdbcPasswordAuthentication.Limits.defaults());
            var executor = new JdbcPlatformExecutor(PlatformDatabase.source(environment, "LS_PLATFORM_EXECUTOR_DB_", JdbcPlatformExecutor.ROLE),
                    PlatformDatabase.source(environment, "LS_PLATFORM_SECURITY_LOG_DB_", JdbcPlatformExecutor.SECURITY_LOG_ROLE), authentication);
            String username = console.readLine("OWNER username: ");
            password = console.readPassword("Confirm definitive OWNER password for this operation: ");
            var result = executor.execute(username, password, target, action);
            console.printf("%s correlation=%s evidence=%s%n", result.outcome(), result.correlation(), result.evidenceRecorded());
            exit = result.outcome() == JdbcPlatformExecutor.Outcome.CONFIRMED ? 0 : 1;
        } catch (RuntimeException failure) {
            System.err.println("Platform operation unavailable");
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
        if (exit != 0) {
            System.exit(exit);
        }
    }
}
