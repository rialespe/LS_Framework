package com.lssoluciones.lscore.session;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Metadata and bounded revocation only. Identities come exclusively from the V16 transaction. */
public final class JdbcSessionManagement {
    private final JdbcApplicationSessions sessions;

    public JdbcSessionManagement(JdbcApplicationSessions sessions) {
        if (!sessions.multidevice()) {
            throw new IllegalArgumentException("Multidevice is disabled");
        }
        this.sessions = sessions;
    }

    public record Entry(UUID id, boolean current, Instant createdAt, Instant lastSeenAt,
            Instant expiresAt, Instant idleExpiresAt, String status, boolean tenantRevoked) { }

    public List<Entry> list(LoginApplication application, String token, UUID tenant) {
        return sessions.withSession(application, token, (connection, session) -> {
            if (tenant != null) {
                require(admin(connection, session, tenant));
            }
            String scope = tenant == null ? "s.user_id=?" : "EXISTS (SELECT FROM security.session_contexts sc "
                    + "WHERE sc.session_id=s.id AND sc.application_id=s.application_id AND sc.tenant_id=?)";
            String blocked = tenant == null ? "false" : "EXISTS (SELECT FROM security.session_tenant_revocations rv "
                    + "WHERE rv.session_id=s.id AND rv.tenant_id=?)";
            try (var statement = connection.prepareStatement("SELECT s.id,s.id=?,s.created_at,s.last_seen_at,s.expires_at,"
                    + "coalesce(s.last_seen_at,s.created_at)+(? * interval '1 second'),"
                    + "CASE WHEN s.revoked_at IS NOT NULL THEN 'REVOKED' WHEN s.expires_at<=clock_timestamp() THEN 'ABSOLUTE_EXPIRED' "
                    + "WHEN coalesce(s.last_seen_at,s.created_at)<=clock_timestamp()-(? * interval '1 second') "
                    + "THEN 'IDLE_EXPIRED' ELSE 'ACTIVE' END," + blocked
                    + " FROM security.sessions s WHERE s.application_id=? AND " + scope
                    + " ORDER BY (s.id=?) DESC,s.created_at DESC,s.id LIMIT 100")) {
                int index = 1;
                statement.setObject(index++, session.id());
                statement.setLong(index++, sessions.idleSeconds(application));
                statement.setLong(index++, sessions.idleSeconds(application));
                if (tenant != null) {
                    statement.setObject(index++, tenant);
                }
                statement.setObject(index++, session.applicationId());
                statement.setObject(index++, tenant == null ? session.userId() : tenant);
                statement.setObject(index, session.id());
                statement.setQueryTimeout(5);
                var result = new ArrayList<Entry>();
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        var seen = rows.getTimestamp(4);
                        result.add(new Entry(rows.getObject(1, UUID.class), rows.getBoolean(2), rows.getTimestamp(3).toInstant(),
                                seen == null ? null : seen.toInstant(), rows.getTimestamp(5).toInstant(),
                                rows.getTimestamp(6).toInstant(), rows.getString(7), rows.getBoolean(8)));
                    }
                }
                return List.copyOf(result);
            }
        });
    }

    public boolean authorized(LoginApplication application, String token, UUID tenant) {
        return sessions.withSession(application, token, (connection, session) -> admin(connection, session, tenant));
    }

    public int revoke(LoginApplication application, String token, UUID target, boolean others) {
        return sessions.withSession(application, token, (connection, session) -> {
            try (var statement = connection.prepareStatement("UPDATE security.sessions SET revoked_at=clock_timestamp(),"
                    + "updated_at=clock_timestamp() WHERE user_id=? AND application_id=? AND revoked_at IS NULL AND "
                    + (others ? "id<>?" : "id=?"))) {
                statement.setObject(1, session.userId());
                statement.setObject(2, session.applicationId());
                statement.setObject(3, others ? session.id() : target);
                statement.setQueryTimeout(5);
                int count = statement.executeUpdate();
                require(others || count == 1);
                return count;
            }
        });
    }

    public void revokeTenant(LoginApplication application, String token, UUID tenant, UUID target) {
        sessions.withSession(application, token, (connection, session) -> {
            require(admin(connection, session, tenant));
            UUID owner;
            try (var statement = connection.prepareStatement("SELECT s.user_id FROM security.sessions s WHERE s.id=? AND s.application_id=? "
                    + "AND EXISTS (SELECT FROM security.session_contexts sc WHERE sc.session_id=s.id AND sc.tenant_id=? "
                    + "AND sc.application_id=s.application_id) FOR UPDATE OF s")) {
                statement.setObject(1, target);
                statement.setObject(2, session.applicationId());
                statement.setObject(3, tenant);
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) {
                    require(rows.next());
                    owner = rows.getObject(1, UUID.class);
                }
            }
            try (var statement = connection.prepareStatement("INSERT INTO security.session_tenant_revocations "
                    + "(session_id,user_id,application_id,tenant_id,revoked_by_user_id) SELECT ?,?,?,?,? "
                    + "WHERE NOT EXISTS (SELECT FROM security.session_tenant_revocations WHERE session_id=? AND tenant_id=?)")) {
                statement.setObject(1, target);
                statement.setObject(2, owner);
                statement.setObject(3, session.applicationId());
                statement.setObject(4, tenant);
                statement.setObject(5, session.userId());
                statement.setObject(6, target);
                statement.setObject(7, tenant);
                statement.setQueryTimeout(5);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("UPDATE security.session_contexts SET revoked_at=clock_timestamp(),"
                    + "updated_at=clock_timestamp() WHERE session_id=? AND tenant_id=? AND application_id=? AND revoked_at IS NULL")) {
                statement.setObject(1, target);
                statement.setObject(2, tenant);
                statement.setObject(3, session.applicationId());
                statement.setQueryTimeout(5);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static boolean admin(Connection connection, JdbcApplicationSessions.Session session, UUID tenant) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT EXISTS (SELECT FROM tenant.administrators ad "
                + "JOIN tenant.memberships m ON m.tenant_id=ad.tenant_id AND m.id=ad.membership_id "
                + "JOIN tenant.tenants t ON t.id=m.tenant_id "
                + "JOIN catalog.tenant_applications ta ON ta.tenant_id=t.id "
                + "WHERE ad.tenant_id=? AND m.user_id=? AND ta.application_id=? "
                + "AND ad.status='ACTIVE' AND ad.revoked_at IS NULL AND m.status='ACTIVE' AND t.status='ACTIVE' AND ta.status='ACTIVE' "
                + "AND NOT EXISTS (SELECT FROM security.session_tenant_revocations rv WHERE rv.session_id=? AND rv.tenant_id=t.id))")) {
            statement.setObject(1, tenant);
            statement.setObject(2, session.userId());
            statement.setObject(3, session.applicationId());
            statement.setObject(4, session.id());
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new JdbcSessionContexts.Denied();
        }
    }
}
