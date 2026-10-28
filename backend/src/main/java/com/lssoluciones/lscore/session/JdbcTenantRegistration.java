package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.platform.PlatformDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;

/** Core-only session composition, independent global authorization and fresh F04 for every creation/replay. */
public final class JdbcTenantRegistration {
    public static final String ROLE = "ls_core_tenant_registrar_dev";
    public static final String LOG_ROLE = "ls_core_tenant_registration_log_dev";
    private final JdbcApplicationSessions sessions;
    private final JdbcPasswordAuthentication authentication;
    private final DataSource source;
    private final DataSource log;

    public JdbcTenantRegistration(JdbcApplicationSessions sessions, JdbcPasswordAuthentication authentication, DataSource source, DataSource log) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.source = source;
        this.log = log;
    }

    public record Authority(String kind, boolean canRegister) { }
    public record Administrator(UUID id, String displayName, String status) { }
    public record Tenant(UUID id, String name, String status, UUID administratorId, Instant createdAt, UUID requestId) { }
    public record Result(String outcome, UUID correlation, Tenant tenant) { }

    public Authority authority(String token) {
        return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, false);
                var result = authorized(connection, session.userId());
                connection.commit();
                return result;
            }
        });
    }

    public Administrator administrator(String token, String username) {
        return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, false);
                authorized(connection, session.userId());
                Administrator result = null;
                try (var statement = connection.prepareStatement("SELECT id,display_name,status FROM identity.users WHERE lower(btrim(username))=lower(btrim(?))")) {
                    statement.setString(1, username == null || username.length() > 1024 ? "" : username);
                    try (var rows = statement.executeQuery()) {
                        if (rows.next()) {
                            result = new Administrator(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3));
                        }
                    }
                }
                connection.commit();
                return result;
            }
        });
    }

    public Tenant find(String token, UUID request) {
        return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, false);
                locks(connection, request);
                authorized(connection, session.userId());
                var result = read(connection, request, session.userId());
                connection.commit();
                return result;
            }
        });
    }

    public Result register(String token, UUID request, String rawName, UUID administrator, char[] password) {
        char[] secret = password == null ? null : password.clone();
        Result[] decided = new Result[1];
        try {
            // The session transaction owns its lock throughout the administrative operation; no subject capability escapes.
            return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
                decided[0] = create(session.userId(), request, rawName, administrator, secret);
                return decided[0];
            });
        } catch (JdbcApplicationSessions.Rejected failure) {
            if (decided[0] != null) {
                return decided[0]; // A later activity COMMIT cannot undo the independently acknowledged administrative result.
            }
            throw failure;
        } finally {
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
        }
    }

    private Result create(UUID actor, UUID request, String rawName, UUID administrator, char[] password) {
        boolean committing = false;
        boolean confirmed = false;
        Tenant tenant = null;
        UUID correlation = request == null ? UUID.randomUUID() : request;
        try (var connection = source.getConnection()) {
            PlatformDatabase.begin(connection, ROLE, false);
            try {
                String username;
                java.sql.Timestamp started;
                try (var statement = connection.prepareStatement("SELECT username,clock_timestamp() FROM identity.users WHERE id=? AND status='ACTIVE'")) {
                    statement.setObject(1, actor);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new Denied();
                        }
                        username = rows.getString(1);
                        started = rows.getTimestamp(2);
                    }
                }
                if (!actor.equals(authentication.verify(username, password))) {
                    throw new Denied();
                }
                String name = name(rawName);
                if (request == null || administrator == null) {
                    throw new Denied();
                }
                locks(connection, request);
                authorized(connection, actor);
                try (var statement = connection.prepareStatement("SELECT clock_timestamp()<=?::timestamptz+interval '10 seconds'")) {
                    statement.setTimestamp(1, started);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || !rows.getBoolean(1)) {
                            throw new Denied();
                        }
                    }
                }
                tenant = read(connection, request, actor);
                if (tenant != null) {
                    if (!tenant.name().equals(name) || !tenant.administratorId().equals(administrator)) {
                        throw new Denied();
                    }
                } else {
                    try (var statement = connection.prepareStatement("INSERT INTO tenant.initial_registrations(request_id,actor_user_id,administrator_user_id,name) VALUES (?,?,?,?)")) {
                        statement.setObject(1, request);
                        statement.setObject(2, actor);
                        statement.setObject(3, administrator);
                        statement.setString(4, name);
                        statement.executeUpdate();
                    }
                    tenant = read(connection, request, actor);
                    if (tenant == null) {
                        throw new Denied();
                    }
                }
                committing = true;
                connection.commit();
                confirmed = true;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            // Neutral result. No SQL or credential diagnostics, no retry after an uncertain COMMIT.
        }
        if (confirmed) {
            return new Result("CONFIRMED", correlation, tenant);
        }
        boolean recorded = rejection(actor, correlation, committing);
        return new Result(committing ? "UNKNOWN" : recorded ? "DENIED" : "UNAVAILABLE", correlation, null);
    }

    private boolean rejection(UUID actor, UUID correlation, boolean uncertain) {
        try (var connection = log.getConnection()) {
            PlatformDatabase.begin(connection, LOG_ROLE, false);
            try (var statement = connection.prepareStatement("INSERT INTO audit.events(occurred_at,actor_kind,actor_user_id,action_code,resource_type,outcome,correlation_id,metadata) "
                    + "VALUES(clock_timestamp(),'USER',?,'TENANT_REGISTER','TENANT',?,?,'{}'::jsonb)")) {
                statement.setObject(1, actor);
                statement.setString(2, uncertain ? "UNKNOWN" : "DENIED");
                statement.setObject(3, correlation);
                statement.executeUpdate();
                connection.commit();
                return true;
            }
        } catch (SQLException | RuntimeException failure) {
            return false;
        }
    }

    private static Authority authorized(Connection connection, UUID actor) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT a.administrator_kind,(a.administrator_kind='OWNER' OR EXISTS(SELECT FROM platform.tenant_registration_grants g WHERE g.designation_id=a.id)) "
                + "FROM platform.administrators a JOIN identity.users u ON u.id=a.user_id WHERE a.user_id=? AND a.status='ACTIVE' AND a.revoked_at IS NULL AND u.status='ACTIVE'")) {
            statement.setObject(1, actor);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(2)) {
                    throw new Denied();
                }
                return new Authority(rows.getString(1), true);
            }
        }
    }

    private static void locks(Connection connection, UUID request) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_xact_lock(370019,1)");
        }
        try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?::text,400022))")) {
            statement.setString(1, request.toString());
            statement.execute();
        }
    }

    private static Tenant read(Connection connection, UUID request, UUID actor) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT t.id,t.name,t.status,r.administrator_user_id,r.created_at,r.request_id FROM tenant.initial_registrations r "
                + "JOIN tenant.tenants t ON t.id=r.tenant_id WHERE r.request_id=? AND r.actor_user_id=?")) {
            statement.setObject(1, request);
            statement.setObject(2, actor);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? new Tenant(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getObject(4, UUID.class),
                        rows.getTimestamp(5).toInstant(), rows.getObject(6, UUID.class)) : null;
            }
        }
    }

    private static String name(String raw) {
        if (raw == null) {
            throw new Denied();
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip().replaceAll("(?U)\\s+", " ");
        if (value.isBlank() || value.codePointCount(0, value.length()) > 200 || value.chars().anyMatch(Character::isISOControl)) {
            throw new Denied();
        }
        return value;
    }

    public static final class Denied extends RuntimeException {
        public Denied() { super("Registration unavailable"); }
    }
}
