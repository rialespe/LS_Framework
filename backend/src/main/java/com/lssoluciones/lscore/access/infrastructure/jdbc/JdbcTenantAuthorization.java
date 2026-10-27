package com.lssoluciones.lscore.access.infrastructure.jdbc;

import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Internal context-selection query, separate from business SQL admission/execution.
 * F04 authenticates every invocation; its returned UUID is consumed here, never accepted from a client.
 * One READ COMMITTED SELECT provides coherent current evidence. A later change can invalidate the snapshot.
 * No session/API Key evidence is fabricated. Only the trusted operation coordinator can use internal subject data.
 */
public final class JdbcTenantAuthorization {
    public static final String ROLE = "ls_core_authorization_reader_dev";
    private static final String IDENTITY = "SELECT session_user,current_user,current_database(),"
            + "rolcanlogin AND NOT (rolsuper OR rolbypassrls OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication) "
            + "AND rolconnlimit=3 AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) "
            + "AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass "
            + "AND refobjid=r.oid AND deptype='o') FROM pg_roles r WHERE rolname=session_user";
    /** Shared relationship graph for internal password/session metadata readers; conveys no identity. */
    public static final String CONTEXT_RELATIONSHIPS = "FROM identity.users u "
            + "JOIN tenant.memberships m ON m.user_id=u.id "
            + "JOIN tenant.tenants t ON t.id=m.tenant_id "
            + "JOIN catalog.tenant_applications ta ON ta.tenant_id=t.id "
            + "JOIN catalog.applications a ON a.id=ta.application_id "
            + "JOIN tenant.branches b ON b.tenant_id=t.id "
            + "JOIN tenant.branch_memberships bm ON bm.tenant_id=t.id AND bm.membership_id=m.id AND bm.branch_id=b.id ";
    private static final String LOOKUP = "SELECT u.status,t.status,m.status,b.status,bm.status,a.status,ta.status,"
            + "clock_timestamp(),m.id,bm.id " + CONTEXT_RELATIONSHIPS
            + "WHERE t.id=? AND a.id=? AND b.id=? AND u.id=? LIMIT 2";
    private final DataSource source;
    private final JdbcPasswordAuthentication authentication;

    public JdbcTenantAuthorization(DataSource source, JdbcPasswordAuthentication authentication) {
        this.source = Objects.requireNonNull(source);
        this.authentication = Objects.requireNonNull(authentication);
    }

    /** The only subject entry point: selectors constrain an identity freshly verified through real F04. */
    public Decision evaluate(String username, char[] password, Scope requested) {
        try {
            if (requested == null || requested.tenantId() == null || requested.applicationId() == null
                    || requested.branchId() == null) {
                return Decision.denied();
            }
            UUID authenticatedUser = authentication.verify(username, password);
            if (authenticatedUser == null) {
                return Decision.denied();
            }
            return lookup(authenticatedUser, requested);
        } catch (SQLException | RuntimeException unavailable) {
            // No subjects, selectors, JDBC diagnostics or failure causes escape this boundary.
            return Decision.denied();
        }
    }

    private Decision lookup(UUID authenticatedUser, Scope requested) throws SQLException {
        Decision result;
        try (var connection = source.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                checkIdentity(connection);
                result = read(connection, authenticatedUser, requested);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
        return result; // No positive decision before acknowledged commit and physical connection close.
    }

    private static Decision read(Connection connection, UUID user, Scope scope) throws SQLException {
        try (var statement = connection.prepareStatement(LOOKUP)) {
            statement.setObject(1, scope.tenantId());
            statement.setObject(2, scope.applicationId());
            statement.setObject(3, scope.branchId());
            statement.setObject(4, user);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Decision.denied();
                }
                boolean eligible = TenantContextPolicy.eligible(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getString(5), rows.getString(6), rows.getString(7));
                Instant observedAt = rows.getTimestamp(8).toInstant();
                UUID membership = rows.getObject(9, UUID.class);
                UUID branchMembership = rows.getObject(10, UUID.class);
                if (rows.next() || !eligible) {
                    return Decision.denied();
                }
                return new Decision(Outcome.ALLOWED, scope, observedAt, user, membership, branchMembership);
            }
        }
    }

    private static void checkIdentity(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(IDENTITY)) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !ROLE.equals(rows.getString(1)) || !ROLE.equals(rows.getString(2))
                        || !"ls_core_dev".equals(rows.getString(3)) || !rows.getBoolean(4)) {
                    throw new SQLException();
                }
            }
        }
    }

    public enum Outcome { ALLOWED, DENIED }

    /**
     * Historical decision data only, NOT an AuthenticatedIdentity, AuthorizedContext or AuthorizedAdmission.
     * No execution/admission API accepts this type. Re-evaluate with credentials before future use;
     * even a newly evaluated result does not grant SQL privileges or guarantee concurrent revocation.
     */
    public static final class Decision {
        private final Outcome outcome;
        private final Scope scope;
        private final Instant observedAt;
        private final UUID subject;
        private final UUID membership;
        private final UUID branchMembership;

        private Decision(Outcome outcome, Scope scope, Instant observedAt, UUID subject, UUID membership, UUID branchMembership) {
            this.outcome = outcome;
            this.scope = scope;
            this.observedAt = observedAt;
            this.subject = subject;
            this.membership = membership;
            this.branchMembership = branchMembership;
        }

        private static Decision denied() {
            return new Decision(Outcome.DENIED, null, null, null, null, null);
        }

        UUID subject() {
            return subject;
        }

        UUID membership() {
            return membership;
        }

        UUID branchMembership() {
            return branchMembership;
        }

        public Outcome outcome() {
            return outcome;
        }

        public Optional<Scope> authorizedScope() {
            return Optional.ofNullable(scope);
        }

        public Optional<Instant> observedAt() {
            return Optional.ofNullable(observedAt);
        }

        @Override
        public String toString() {
            return "TenantAuthorizationDecision[" + outcome + "]";
        }
    }
}
