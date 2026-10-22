package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.ManualIdentityInput;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Administrative creation only; the dedicated PostgreSQL LOGIN is the authority, never caller claims. */
public final class JdbcManualIdentityRegistration {
    public static final String DATABASE_ROLE = "ls_core_identity_registrar_dev";
    private static final String IDENTITY = "SELECT session_user,current_user,r.rolcanlogin AND "
            + "NOT (r.rolsuper OR r.rolbypassrls OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication "
            + "OR r.rolinherit) AND r.rolconnlimit=3 "
            + "AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid) "
            + "AND NOT EXISTS(SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass "
            + "AND refobjid=r.oid AND deptype='o') FROM pg_roles r WHERE r.rolname=session_user";
    private static final String INSERT = "INSERT INTO identity.users (id,username,display_name,email) VALUES (?,?,?,?)";
    private final DataSource source;

    public JdbcManualIdentityRegistration(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    /**
     * Preserves input spelling; V7 arbitrates uniqueness atomically, without availability queries.
     * V11 supplies PENDING and enforces unverified email. No credentials or other identity data are written.
     * A failure at COMMIT/close can have an uncertain outcome; no automatic retry or upsert is attempted.
     */
    public UUID register(ManualIdentityInput input) {
        if (input == null) {
            throw new RegistrationRejectedException();
        }
        UUID id = UUID.randomUUID();
        try (var connection = source.getConnection()) {
            connection.setReadOnly(false);
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement(IDENTITY)) {
                    statement.setQueryTimeout(5);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || !DATABASE_ROLE.equals(rows.getString(1))
                                || !DATABASE_ROLE.equals(rows.getString(2)) || !rows.getBoolean(3)) {
                            throw new SQLException("Unexpected registration identity");
                        }
                    }
                }
                try (var statement = connection.prepareStatement(INSERT)) {
                    statement.setQueryTimeout(5);
                    statement.setObject(1, id);
                    statement.setString(2, input.username());
                    statement.setString(3, input.displayName());
                    statement.setString(4, input.email());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Registration incomplete");
                    }
                }
                connection.commit();
                return id;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            // Uniqueness, ACL, RLS, connection and other JDBC failures share a neutral response.
            throw new RegistrationRejectedException();
        }
    }

    public static final class RegistrationRejectedException extends RuntimeException {
        private RegistrationRejectedException() {
            super("Identity registration unavailable");
        }
    }
}
