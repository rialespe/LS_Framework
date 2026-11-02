package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordAuthentication;
import com.lssoluciones.lscore.platform.PlatformDatabase;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/** Core RBAC coordinator. Platform-owned ceilings are independent of tenant roles. */
public final class JdbcProfileAssignments {
    public static final String ROLE = "ls_core_profile_assignments_dev";
    public static final String LOG_ROLE = "ls_core_profile_assignments_log_dev";
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();
    private static final String RECEIPTS =
            "SELECT"
                + " request_id,tenant_id,application_id,action,membership_id,previous_snapshot,result_snapshot,created_at,actor_user_id"
                + " FROM security.profile_assignment_operations";
    private final JdbcApplicationSessions sessions;
    private final JdbcPasswordAuthentication authentication;
    private final DataSource source;
    private final DataSource log;

    public JdbcProfileAssignments(
            JdbcApplicationSessions sessions,
            JdbcPasswordAuthentication authentication,
            DataSource source,
            DataSource log) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.source = source;
        this.log = log;
    }

    public enum Action {
        JOIN_BRANCH,
        ASSIGN,
        REVOKE
    }

    public record Input(
            UUID membershipId,
            UUID branchId,
            UUID branchMembershipId,
            UUID assignmentId,
            Instant expectedUpdatedAt,
            Long expectedRevision,
            List<UUID> profileIds) {
        public Input {
            profileIds = profileIds == null ? List.of() : List.copyOf(profileIds);
        }
    }

    public record Receipt(
            UUID requestId,
            UUID tenantId,
            UUID applicationId,
            String action,
            UUID membershipId,
            tools.jackson.databind.JsonNode previous,
            tools.jackson.databind.JsonNode result,
            Instant createdAt,
            UUID actorId) {}

    public record Result(String outcome, UUID correlation, Receipt receipt) {}

    public record Application(UUID id, String code, String name, String status) {}

    public record Workspace(
            boolean canManage,
            tools.jackson.databind.JsonNode member,
            List<tools.jackson.databind.JsonNode> branches,
            List<tools.jackson.databind.JsonNode> profiles,
            List<tools.jackson.databind.JsonNode> assignments,
            List<Receipt> history) {}

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection, UUID actor) throws SQLException;
    }

    private <T> T read(String token, Work<T> work) {
        return sessions.withSession(
                LoginApplication.CORE,
                token,
                (ignored, session) -> {
                    try (var connection = source.getConnection()) {
                        PlatformDatabase.begin(connection, ROLE, false);
                        lock(connection, true);
                        T value = work.run(connection, session.userId());
                        connection.commit();
                        return value;
                    } catch (SQLException failure) {
                        throw new JdbcTenantUsers.Unavailable();
                    }
                });
    }

    public List<JdbcTenantUsers.Company> companies(String token) {
        return read(
                token,
                (connection, actor) -> {
                    var values = new ArrayList<JdbcTenantUsers.Company>();
                    try (var query =
                            connection.prepareStatement(
                                    "SELECT id,name,status FROM tenant.tenants t WHERE"
                                        + " t.status='ACTIVE' AND"
                                        + " (security.rbac_authority(?,t.id,NULL,false) OR"
                                        + " EXISTS(SELECT FROM catalog.tenant_applications a WHERE"
                                        + " a.tenant_id=t.id AND"
                                        + " security.rbac_authority(?,t.id,a.application_id,false)))"
                                        + " ORDER BY name,id LIMIT 1001")) {
                        query.setObject(1, actor);
                        query.setObject(2, actor);
                        try (var rows = query.executeQuery()) {
                            while (rows.next()) {
                                values.add(
                                        new JdbcTenantUsers.Company(
                                                rows.getObject(1, UUID.class),
                                                rows.getString(2),
                                                rows.getString(3)));
                            }
                        }
                    }
                    if (values.isEmpty()) {
                        try (var query =
                                connection.prepareStatement("SELECT security.rbac_owner(?)")) {
                            query.setObject(1, actor);
                            try (var rows = query.executeQuery()) {
                                require(rows.next() && rows.getBoolean(1));
                            }
                        }
                    }
                    return bounded(values);
                });
    }

    public List<Application> applications(String token, UUID tenant) {
        return read(
                token,
                (connection, actor) -> {
                    try (var query =
                            connection.prepareStatement(
                                    "SELECT security.rbac_authority(?,?,NULL,false) OR"
                                        + " EXISTS(SELECT FROM catalog.tenant_applications a WHERE"
                                        + " a.tenant_id=? AND"
                                        + " security.rbac_authority(?,?,a.application_id,false))")) {
                        query.setObject(1, actor);
                        query.setObject(2, tenant);
                        query.setObject(3, tenant);
                        query.setObject(4, actor);
                        query.setObject(5, tenant);
                        try (var rows = query.executeQuery()) {
                            require(rows.next() && rows.getBoolean(1));
                        }
                    }
                    var values = new ArrayList<Application>();
                    try (var query =
                            connection.prepareStatement(
                                    "SELECT a.id,a.code,a.name,t.status FROM"
                                        + " catalog.tenant_applications t JOIN catalog.applications"
                                        + " a ON a.id=t.application_id WHERE t.tenant_id=? AND"
                                        + " a.status='PUBLISHED' AND"
                                        + " security.rbac_authority(?,?,a.id,false) ORDER BY"
                                        + " a.name,a.id LIMIT 1001")) {
                        query.setObject(1, tenant);
                        query.setObject(2, actor);
                        query.setObject(3, tenant);
                        try (var rows = query.executeQuery()) {
                            while (rows.next()) {
                                values.add(
                                        new Application(
                                                rows.getObject(1, UUID.class),
                                                rows.getString(2),
                                                rows.getString(3),
                                                rows.getString(4)));
                            }
                        }
                    }
                    return bounded(values);
                });
    }

    public Workspace workspace(String token, UUID tenant, UUID app, UUID member) {
        return read(
                token,
                (connection, actor) -> {
                    boolean manage = authorize(connection, actor, tenant, app);
                    var members =
                            json(
                                    connection,
                                    "SELECT"
                                        + " jsonb_build_object('id',m.id,'userId',u.id,'username',u.username,'name',u.display_name,'status',m.status)"
                                        + " FROM tenant.memberships m JOIN identity.users u ON"
                                        + " u.id=m.user_id WHERE m.tenant_id=? AND m.id=? LIMIT 1",
                                    tenant,
                                    member);
                    if (members.isEmpty()) {
                        throw new JdbcTenantUsers.Missing();
                    }
                    var branches =
                            json(
                                    connection,
                                    "SELECT"
                                        + " jsonb_build_object('id',b.id,'code',b.code,'name',b.name,'status',b.status,'branchMembershipId',bm.id,'accessStatus',bm.status,'updatedAt',bm.updated_at)"
                                        + " FROM tenant.branches b LEFT JOIN"
                                        + " tenant.branch_memberships bm ON"
                                        + " bm.tenant_id=b.tenant_id AND bm.branch_id=b.id AND"
                                        + " bm.membership_id=? WHERE b.tenant_id=? ORDER BY"
                                        + " b.code,b.id LIMIT 1001",
                                    member,
                                    tenant);
                    var profiles =
                            json(
                                    connection,
                                    "SELECT"
                                        + " jsonb_build_object('id',p.id,'code',p.code,'name',p.name,'status',p.status,'assignable',security.profile_assignable(p.tenant_id,p.application_id,p.id),'permissions',coalesce((SELECT"
                                        + " jsonb_agg(DISTINCT"
                                        + " jsonb_build_object('id',pe.id,'code',pe.code,'scope',pe.resource_scope,'operationKind',e.operation_kind,'contextMode',e.context_mode))"
                                        + " FROM security.profile_roles pr JOIN"
                                        + " security.role_permissions rp ON"
                                        + " rp.tenant_id=pr.tenant_id AND"
                                        + " rp.application_id=pr.application_id AND"
                                        + " rp.role_id=pr.role_id JOIN security.permissions pe ON"
                                        + " pe.application_id=rp.application_id AND"
                                        + " pe.id=rp.permission_id LEFT JOIN"
                                        + " platform.permission_execution_rules e ON"
                                        + " e.application_id=pe.application_id AND"
                                        + " e.permission_id=pe.id WHERE pr.tenant_id=p.tenant_id"
                                        + " AND pr.application_id=p.application_id AND"
                                        + " pr.profile_id=p.id),'[]'::jsonb)) FROM"
                                        + " security.profiles p WHERE tenant_id=? AND"
                                        + " application_id=? ORDER BY code,id LIMIT 1001",
                                    tenant,
                                    app);
                    var assignments =
                            json(
                                    connection,
                                    "SELECT to_jsonb(a) FROM security.branch_profile_assignments a"
                                        + " JOIN tenant.branch_memberships bm ON"
                                        + " bm.tenant_id=a.tenant_id AND"
                                        + " bm.id=a.branch_membership_id WHERE a.tenant_id=? AND"
                                        + " a.application_id=? AND bm.membership_id=? ORDER BY"
                                        + " a.created_at DESC,a.id LIMIT 1001",
                                    tenant,
                                    app,
                                    member);
                    return new Workspace(
                            manage,
                            members.getFirst(),
                            branches,
                            profiles,
                            assignments,
                            history(connection, tenant, app, member));
                });
    }

    /** Administrative projection only; never an operational authorization proof. */
    public List<tools.jackson.databind.JsonNode> preview(
            String token, UUID tenant, UUID app, UUID member, UUID branchMember) {
        return read(
                token,
                (connection, actor) -> {
                    authorize(connection, actor, tenant, app);
                    if (json(connection, "SELECT to_jsonb(id) FROM tenant.branch_memberships WHERE tenant_id=? AND membership_id=? AND id=?", tenant, member, branchMember).isEmpty()) {
                        throw new JdbcTenantUsers.Missing();
                    }
                    return json(
                            connection,
                            "SELECT to_jsonb(p) FROM tenant.branch_memberships bm JOIN"
                                + " tenant.memberships m ON m.tenant_id=bm.tenant_id AND"
                                + " m.id=bm.membership_id CROSS JOIN LATERAL"
                                + " security.context_permission_set(m.user_id,bm.tenant_id,?,bm.branch_id,m.id,bm.id)"
                                + " p WHERE bm.tenant_id=? AND bm.membership_id=? AND bm.id=? ORDER"
                                + " BY p.code,p.id LIMIT 1001",
                            app,
                            tenant,
                            member,
                            branchMember);
                });
    }

    public List<tools.jackson.databind.JsonNode> members(String token, UUID tenant, UUID app) {
        return read(
                token,
                (connection, actor) -> {
                    authorize(connection, actor, tenant, app);
                    return json(
                            connection,
                            "SELECT"
                                + " jsonb_build_object('id',m.id,'name',u.display_name,'username',u.username,'status',m.status)"
                                + " FROM tenant.memberships m JOIN identity.users u ON"
                                + " u.id=m.user_id WHERE m.tenant_id=? ORDER BY u.username,m.id"
                                + " LIMIT 1001",
                            tenant);
                });
    }

    private static List<tools.jackson.databind.JsonNode> json(
            Connection connection, String sql, UUID... ids) throws SQLException {
        var values = new ArrayList<tools.jackson.databind.JsonNode>();
        try (var query = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                query.setObject(i + 1, ids[i]);
            }
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    values.add(JSON.readTree(rows.getString(1)));
                }
            }
        }
        return bounded(values);
    }

    private static List<Receipt> history(Connection connection, UUID tenant, UUID app, UUID member)
            throws SQLException {
        var values = new ArrayList<Receipt>();
        try (var query =
                connection.prepareStatement(
                        RECEIPTS
                                + " WHERE tenant_id=? AND application_id=? AND membership_id=?"
                                + " ORDER BY created_at DESC,request_id DESC LIMIT 50")) {
            query.setObject(1, tenant);
            query.setObject(2, app);
            query.setObject(3, member);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    values.add(receipt(rows));
                }
            }
        }
        return List.copyOf(values);
    }

    private static boolean authorize(Connection connection, UUID actor, UUID tenant, UUID app)
            throws SQLException {
        try (var query =
                connection.prepareStatement(
                        "SELECT"
                            + " security.rbac_authority(?,?,?,false),security.rbac_authority(?,?,?,true)")) {
            query.setObject(1, actor);
            query.setObject(2, tenant);
            query.setObject(3, app);
            query.setObject(4, actor);
            query.setObject(5, tenant);
            query.setObject(6, app);
            try (var rows = query.executeQuery()) {
                require(rows.next() && rows.getBoolean(1));
                return rows.getBoolean(2);
            }
        }
    }

    private static <T> List<T> bounded(List<T> values) {
        if (values.size() > 1000) {
            throw new JdbcTenantUsers.Unavailable();
        }
        return List.copyOf(values);
    }

    public Result find(String token, UUID request) {
        return read(
                token,
                (connection, actor) -> {
                    Receipt value = find(connection, actor, request);
                    return value == null ? null : new Result("CONFIRMED", request, value);
                });
    }

    private static Receipt find(Connection connection, UUID actor, UUID request)
            throws SQLException {
        try (var query =
                connection.prepareStatement(RECEIPTS + " WHERE request_id=? AND actor_user_id=?")) {
            query.setObject(1, request);
            query.setObject(2, actor);
            try (var rows = query.executeQuery()) {
                return rows.next() ? receipt(rows) : null;
            }
        }
    }

    public Result mutate(
            String token,
            UUID tenant,
            UUID application,
            UUID request,
            Action action,
            Input input,
            char[] password) {
        if (request == null
                || action == null
                || tenant == null
                || application == null
                || input == null
                || input.profileIds().size() > 50
                || input.membershipId() == null
                || input.branchId() == null) {
            throw new JdbcTenantUsers.Invalid();
        }
        Result[] decided = new Result[1];
        try {
            return sessions.withSession(
                    LoginApplication.CORE,
                    token,
                    (ignored, session) -> {
                        decided[0] =
                                execute(
                                        session.userId(),
                                        tenant,
                                        application,
                                        request,
                                        action,
                                        input,
                                        password);
                        return decided[0];
                    });
        } catch (JdbcApplicationSessions.Rejected failure) {
            if (decided[0] != null) {
                return decided[0];
            }
            throw failure;
        }
    }

    private Result execute(
            UUID actor,
            UUID tenant,
            UUID application,
            UUID request,
            Action action,
            Input input,
            char[] password) {
        boolean committing = false;
        String outcome = "UNAVAILABLE";
        UUID company = tenant;
        try (var connection = source.getConnection()) {
            PlatformDatabase.begin(connection, ROLE, false);
            try {
                java.sql.Timestamp started;
                String actorName;
                try (var query =
                        connection.prepareStatement(
                                "SELECT username,clock_timestamp()"
                                        + " FROM identity.users WHERE id=? AND status='ACTIVE'")) {
                    query.setObject(1, actor);
                    try (var rows = query.executeQuery()) {
                        require(rows.next());
                        actorName = rows.getString(1);
                        started = rows.getTimestamp(2);
                    }
                }
                require(actor.equals(authentication.verify(actorName, password)));
                lock(connection, false);
                Receipt prior = find(connection, actor, request);
                if (prior != null) {
                    boolean same;
                    try (var query =
                            connection.prepareStatement(
                                    "SELECT tenant_id=? AND application_id=? AND action=? AND"
                                        + " input=jsonb_build_object('membershipId',?::uuid,'branchId',?::uuid,'branchMembershipId',?::uuid,'assignmentId',?::uuid,'expectedUpdatedAt',?::timestamptz,'expectedRevision',?::bigint,'profileIds',?::uuid[])"
                                        + " FROM security.profile_assignment_operations WHERE"
                                        + " request_id=? AND actor_user_id=?")) {
                        query.setObject(1, tenant);
                        query.setObject(2, application);
                        query.setString(3, action.name());
                        bindInput(query, connection, 4, input);
                        query.setObject(11, request);
                        query.setObject(12, actor);
                        try (var rows = query.executeQuery()) {
                            same = rows.next() && rows.getBoolean(1);
                        }
                    }
                    connection.rollback();
                    return new Result(
                            same ? "CONFIRMED" : "CONFLICT", request, same ? prior : null);
                }
                try (var query =
                        connection.prepareStatement(
                                "SELECT id FROM tenant.tenants WHERE id=? FOR UPDATE")) {
                    query.setObject(1, company);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) {
                            throw new JdbcTenantUsers.Missing();
                        }
                    }
                }
                // Pin the actor's proof until COMMIT. The command locks the remaining eligibility
                // set.
                for (String query :
                        new String[] {
                            "SELECT id FROM identity.users WHERE id=? FOR SHARE",
                            "SELECT user_id FROM identity.user_credentials WHERE user_id=? FOR"
                                        + " SHARE"
                        }) {
                    try (var pin = connection.prepareStatement(query)) {
                        pin.setObject(1, actor);
                        try (var rows = pin.executeQuery()) {
                            require(rows.next());
                        }
                    }
                }
                try (var query =
                        connection.prepareStatement(
                                "SELECT tenant.administrator_ready(user_id) AND"
                                    + " clock_timestamp()<=?::timestamptz+interval '10 seconds' AND"
                                    + " password_changed_at<=? FROM identity.user_credentials WHERE"
                                    + " user_id=?")) {
                    query.setTimestamp(1, started);
                    query.setTimestamp(2, started);
                    query.setObject(3, actor);
                    try (var rows = query.executeQuery()) {
                        require(rows.next() && rows.getBoolean(1));
                    }
                }
                Receipt value;
                try (var query =
                        connection.prepareStatement(
                                "INSERT INTO security.profile_assignment_operations"
                                    + "(request_id,actor_user_id,tenant_id,application_id,action,membership_id,branch_id,branch_membership_id,assignment_id,expected_updated_at,expected_revision,profile_ids)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?) RETURNING"
                                    + " request_id,tenant_id,application_id,action,membership_id,previous_snapshot,result_snapshot,created_at,actor_user_id")) {
                    bind(query, connection, actor, tenant, application, request, action, input);
                    try (var rows = query.executeQuery()) {
                        require(rows.next());
                        value = receipt(rows);
                    }
                }
                committing = true;
                connection.commit();
                return new Result("CONFIRMED", request, value);
            } catch (SQLException failure) {
                connection.rollback();
                outcome =
                        switch (failure.getSQLState() == null ? "" : failure.getSQLState()) {
                            case "23505", "P3201", "40001", "40P01", "55P03" -> "CONFLICT";
                            case "P3202" -> "INCOMPLETE_REQUIREMENTS";
                            case "P3204" -> "NOT_FOUND";
                            case "22023", "23514" -> "INVALID";
                            case "42501" -> "DENIED";
                            default -> "UNAVAILABLE";
                        };
            } catch (JdbcTenantUsers.Missing failure) {
                connection.rollback();
                outcome = "NOT_FOUND";
            } catch (RuntimeException failure) {
                connection.rollback();
                outcome = "DENIED";
            }
        } catch (SQLException | RuntimeException failure) {
            // Neutral response: never log input or credential-bearing statements.
        }
        boolean recorded =
                company == null || rejection(actor, company, request, action, committing);
        return new Result(
                committing ? "UNKNOWN" : recorded ? outcome : "UNAVAILABLE", request, null);
    }

    private boolean rejection(
            UUID actor, UUID tenant, UUID request, Action action, boolean uncertain) {
        try (var connection = log.getConnection()) {
            PlatformDatabase.begin(connection, LOG_ROLE, false);
            try (var query =
                    connection.prepareStatement(
                            "INSERT INTO audit.events"
                                + "(occurred_at,actor_kind,actor_user_id,action_code,resource_type,resource_id,outcome,correlation_id,metadata)"
                                + " VALUES(clock_timestamp(),'USER',?,?,'TENANT',?,?,?,'{}'::jsonb)")) {
                query.setObject(1, actor);
                query.setString(2, "TENANT_PROFILE_" + action.name());
                query.setObject(3, tenant);
                query.setString(4, uncertain ? "UNKNOWN" : "DENIED");
                query.setObject(5, request);
                query.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            return false;
        }
    }

    private static Receipt receipt(java.sql.ResultSet rows) throws SQLException {
        return new Receipt(
                rows.getObject(1, UUID.class),
                rows.getObject(2, UUID.class),
                rows.getObject(3, UUID.class),
                rows.getString(4),
                rows.getObject(5, UUID.class),
                rows.getString(6) == null ? null : JSON.readTree(rows.getString(6)),
                JSON.readTree(rows.getString(7)),
                rows.getTimestamp(8).toInstant(),
                rows.getObject(9, UUID.class));
    }

    static void bind(
            java.sql.PreparedStatement query,
            Connection connection,
            UUID actor,
            UUID tenant,
            UUID app,
            UUID request,
            Action action,
            Input input)
            throws SQLException {
        query.setObject(1, request);
        query.setObject(2, actor);
        query.setObject(3, tenant);
        query.setObject(4, app);
        query.setString(5, action.name());
        bindInput(query, connection, 6, input);
    }

    private static void bindInput(
            java.sql.PreparedStatement query, Connection connection, int start, Input input)
            throws SQLException {
        query.setObject(start, input.membershipId());
        query.setObject(start + 1, input.branchId());
        query.setObject(start + 2, input.branchMembershipId());
        query.setObject(start + 3, input.assignmentId());
        query.setTimestamp(
                start + 4,
                input.expectedUpdatedAt() == null
                        ? null
                        : java.sql.Timestamp.from(input.expectedUpdatedAt()));
        query.setObject(start + 5, input.expectedRevision());
        query.setArray(
                start + 6,
                connection.createArrayOf("uuid", input.profileIds().toArray(UUID[]::new)));
    }

    private static void lock(Connection connection, boolean read) throws SQLException {
        JdbcTenantUsers.lock(connection, read);
    }

    private static void require(boolean value) {
        if (!value) {
            throw new JdbcTenantUsers.Denied();
        }
    }
}
