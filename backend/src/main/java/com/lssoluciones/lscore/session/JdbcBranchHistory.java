package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.platform.PlatformDatabase;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Separate historical resource contract. A Core cookie is identity, never branch or multibranch permission. */
public final class JdbcBranchHistory {
    public static final String ROLE = "ls_core_branch_history_dev";
    private final JdbcApplicationSessions sessions;
    private final DataSource source;
    public JdbcBranchHistory(JdbcApplicationSessions sessions, DataSource source) {
        this.sessions = sessions;
        this.source = source;
    }
    public record Entry(UUID reference, UUID branchId, String action, String previousStatus, String status, Instant occurredAt) { }
    public record Page(List<Entry> entries, boolean hasMore, UUID correlation) { }
    public Page read(String token, UUID tenant, List<UUID> branches, int page) {
        if (tenant == null || branches == null || branches.isEmpty() || branches.size() > 20 || branches.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(branches).size() != branches.size() || page < 0 || page > 100000) {
            throw new JdbcBranchAdministration.Denied();
        }
        return sessions.withSession(LoginApplication.CORE, token, (ignored, session) -> {
            try (var connection = source.getConnection()) {
                PlatformDatabase.begin(connection, ROLE, false);
                UUID reference = UUID.randomUUID();
                try {
                    // Catalog identities come from the persisted approved resource, not browser authority.
                    try (var statement = connection.prepareStatement("INSERT INTO security.branch_history_requests "
                            + "(request_id,actor_user_id,tenant_id,application_id,module_id,branch_ids) "
                            + "SELECT ?,?,?,a.id,m.id,? FROM catalog.applications a JOIN catalog.modules m ON m.application_id=a.id "
                            + "WHERE a.code='ls-core' AND m.code='branches'")) {
                        statement.setObject(1, reference);
                        statement.setObject(2, session.userId());
                        statement.setObject(3, tenant);
                        statement.setArray(4, connection.createArrayOf("uuid", branches.toArray()));
                        if (statement.executeUpdate() != 1) { throw new JdbcBranchAdministration.Denied(); }
                    }
                    var values = new ArrayList<Entry>();
                    try (var statement = connection.prepareStatement("SELECT request_id,branch_id,action,previous_status,resulting_status,created_at "
                            + "FROM tenant.branch_operations WHERE tenant_id=? AND branch_id=ANY(?) ORDER BY created_at DESC,request_id LIMIT 51 OFFSET ?")) {
                        statement.setObject(1, tenant);
                        statement.setArray(2, connection.createArrayOf("uuid", branches.toArray()));
                        statement.setInt(3, page * 50);
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) {
                                values.add(new Entry(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getString(3),
                                        rows.getString(4), rows.getString(5), rows.getTimestamp(6).toInstant()));
                            }
                        }
                    }
                    // Do not return partial results if the native evidence expired while materializing.
                    try (var statement = connection.prepareStatement("SELECT request_id FROM security.branch_history_requests WHERE request_id=?")) {
                        statement.setObject(1, reference);
                        try (var rows = statement.executeQuery()) { if (!rows.next()) { throw new JdbcBranchAdministration.Denied(); } }
                    }
                    var result = new Page(List.copyOf(values.subList(0, Math.min(50, values.size()))), values.size() > 50, reference);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    if (failure instanceof SQLException rejected && ("42501".equals(rejected.getSQLState())
                            || "P2701".equals(rejected.getSQLState()) || "P2703".equals(rejected.getSQLState()))) {
                        // Rejection evidence has no business data, and cannot change the denied result.
                        try (var evidence = connection.prepareStatement("INSERT INTO security.branch_history_rejections(request_id,actor_user_id,tenant_id,reason) VALUES(?,?,?,?)")) {
                            evidence.setObject(1, reference);
                            evidence.setObject(2, session.userId());
                            evidence.setObject(3, tenant);
                            evidence.setString(4, "P2701".equals(rejected.getSQLState()) ? "TENANT_STATE"
                                    : "P2703".equals(rejected.getSQLState()) ? "MODULE_UNAVAILABLE" : "AUTHORITY");
                            evidence.executeUpdate();
                            connection.commit();
                        } catch (SQLException ignoredEvidence) { connection.rollback(); }
                    }
                    throw failure;
                }
            } catch (SQLException failure) {
                if ("42501".equals(failure.getSQLState()) || "P2701".equals(failure.getSQLState()) || "P2703".equals(failure.getSQLState())) {
                    throw new JdbcBranchAdministration.Denied();
                }
                throw new JdbcBranchAdministration.Unavailable();
            }
        });
    }
}
