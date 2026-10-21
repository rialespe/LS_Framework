package com.lssoluciones.lscore.identity.infrastructure.jdbc;

import com.lssoluciones.lscore.identity.application.PasswordCredentialSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Fixed column-level lookup on a dedicated credential-verifier connection; never uses LS_DB_* or C1/C2. */
public final class JdbcPasswordCredentialSource implements PasswordCredentialSource {
    public static final String DATABASE_ROLE = "ls_core_credential_verifier_dev";
    private static final String IDENTITY = "SELECT session_user, current_user, "
            + "NOT (r.rolsuper OR r.rolbypassrls OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication) "
            + "AND NOT EXISTS(SELECT FROM pg_auth_members m WHERE m.member=r.oid) "
            + "AND NOT EXISTS(SELECT FROM pg_class c WHERE c.relowner=r.oid "
            + "AND c.oid IN ('identity.users'::regclass,'identity.user_credentials'::regclass)) "
            + "FROM pg_roles r WHERE r.rolname=session_user";
    private static final String LOOKUP = "SELECT u.id, u.status, c.password_hash, c.password_expires_at, "
            + "c.locked_until, c.must_change_password FROM identity.users u "
            + "JOIN identity.user_credentials c ON c.user_id=u.id "
            + "WHERE lower(btrim(u.username))=lower(btrim(?)) LIMIT 2";
    private final DataSource source;

    public JdbcPasswordCredentialSource(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    @Override
    public Optional<Credential> findByUsername(String username) {
        try (var connection = source.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement(IDENTITY)) {
                    statement.setQueryTimeout(5);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || !DATABASE_ROLE.equals(rows.getString(1))
                                || !DATABASE_ROLE.equals(rows.getString(2)) || !rows.getBoolean(3)) {
                            throw new SQLException("Unexpected verifier identity");
                        }
                    }
                }
                Optional<Credential> result;
                try (var statement = connection.prepareStatement(LOOKUP)) {
                    statement.setQueryTimeout(5);
                    statement.setString(1, username);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            result = Optional.empty();
                        } else {
                            var expires = rows.getObject(4, OffsetDateTime.class);
                            var locked = rows.getObject(5, OffsetDateTime.class);
                            result = Optional.of(new Credential(rows.getObject(1, UUID.class), rows.getString(2),
                                    rows.getString(3), expires == null ? null : expires.toInstant(),
                                    locked == null ? null : locked.toInstant(), rows.getObject(6, Boolean.class)));
                            if (rows.next()) {
                                throw new SQLException("Ambiguous credential source");
                            }
                        }
                    }
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException failure) {
            throw new CredentialSourceUnavailableException();
        }
    }

    public static final class CredentialSourceUnavailableException extends RuntimeException {
        private CredentialSourceUnavailableException() {
            super("Credential source unavailable");
        }
    }
}
