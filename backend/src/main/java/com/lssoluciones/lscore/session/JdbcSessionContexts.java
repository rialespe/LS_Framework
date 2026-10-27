package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.access.application.context.ContextResolver;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource;
import com.lssoluciones.lscore.access.application.context.TrustedContextSource.Scope;
import com.lssoluciones.lscore.access.infrastructure.jdbc.JdbcTenantAuthorization;
import com.lssoluciones.lscore.access.infrastructure.jdbc.TenantContextPolicy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Contextual metadata only: no AuthenticatedIdentity or AuthorizedContext leaves this transaction. */
public final class JdbcSessionContexts {
    private static final String ACCESS = "SELECT t.id,t.name,b.id,b.code,b.name,m.id,bm.id,"
            + "u.status,t.status,m.status,b.status,bm.status,a.status,ta.status "
            + JdbcTenantAuthorization.CONTEXT_RELATIONSHIPS + "WHERE u.id=? AND a.id=? ";
    private static final String PERMISSIONS = "SELECT DISTINCT p.id,p.code,p.name,p.resource_scope,p.module_id,mo.code,mo.name "
            + "FROM security.branch_profile_assignments ba "
            + "JOIN security.profiles pr ON pr.tenant_id=ba.tenant_id AND pr.application_id=ba.application_id AND pr.id=ba.profile_id "
            + "JOIN security.profile_roles pl ON pl.tenant_id=pr.tenant_id AND pl.application_id=pr.application_id AND pl.profile_id=pr.id "
            + "JOIN security.roles r ON r.tenant_id=pl.tenant_id AND r.application_id=pl.application_id AND r.id=pl.role_id "
            + "JOIN security.role_permissions rp ON rp.tenant_id=r.tenant_id AND rp.application_id=r.application_id AND rp.role_id=r.id "
            + "JOIN security.permissions p ON p.application_id=rp.application_id AND p.id=rp.permission_id "
            + "LEFT JOIN catalog.modules mo ON mo.application_id=p.application_id AND mo.id=p.module_id "
            + "WHERE ba.tenant_id=? AND ba.application_id=? AND ba.branch_membership_id=? "
            + "AND ba.status='ACTIVE' AND ba.revoked_at IS NULL AND pr.status='ACTIVE' AND r.status='ACTIVE' "
            + "AND p.status='PUBLISHED' AND p.resource_scope IN ('TENANT','BRANCH') "
            + "AND (p.module_id IS NULL OR (mo.status='PUBLISHED' AND EXISTS "
            + "(SELECT FROM catalog.tenant_modules tm WHERE tm.tenant_id=ba.tenant_id "
            + "AND tm.application_id=ba.application_id AND tm.module_id=mo.id AND tm.status='ACTIVE'))) ORDER BY p.code,p.id";
    private final JdbcApplicationSessions sessions;

    public JdbcSessionContexts(JdbcApplicationSessions sessions) {
        this.sessions = sessions;
    }

    public record Tenant(UUID id, String name) { }
    public record Branch(UUID id, String code, String name) { }
    public record Permission(UUID id, String code, String name, String resourceScope, UUID moduleId) { }
    public record Module(UUID id, String code, String name, List<Permission> permissions) { }
    public record Context(UUID id, UUID tenantId, UUID applicationId, UUID branchId,
            List<Permission> permissions, List<Module> modules) { }
    private record Access(Tenant tenant, Branch branch, UUID membership, UUID branchMembership) { }

    public List<Tenant> tenants(LoginApplication application, String token) {
        return sessions.withSession(application, token, (connection, session) -> {
            var result = new LinkedHashMap<UUID, Tenant>();
            for (var access : access(connection, session, null, null)) {
                result.put(access.tenant().id(), access.tenant());
            }
            return List.copyOf(result.values());
        });
    }

    public List<Branch> branches(LoginApplication application, String token, UUID tenant) {
        return sessions.withSession(application, token, (connection, session) -> {
            var available = access(connection, session, tenant, null);
            require(!available.isEmpty());
            return available.stream().map(Access::branch).toList();
        });
    }

    public Context select(LoginApplication application, String token, UUID tenant, UUID branch) {
        return sessions.withSession(application, token, (connection, session) -> {
            require(tenant != null && branch != null);
            var available = access(connection, session, tenant, branch);
            require(available.size() == 1);
            var authorized = available.getFirst();
            // The owning session is already locked; the unique partial index is a second barrier.
            try (var statement = connection.prepareStatement("UPDATE security.session_contexts SET revoked_at=clock_timestamp(),"
                    + "updated_at=clock_timestamp() WHERE session_id=? AND revoked_at IS NULL")) {
                statement.setObject(1, session.id());
                statement.setQueryTimeout(5);
                statement.executeUpdate();
            }
            UUID id = UUID.randomUUID();
            try (var statement = connection.prepareStatement("INSERT INTO security.session_contexts "
                    + "(id,session_id,user_id,application_id,tenant_id,membership_id,branch_id,expires_at) VALUES (?,?,?,?,?,?,?,?)")) {
                statement.setObject(1, id);
                statement.setObject(2, session.id());
                statement.setObject(3, session.userId());
                statement.setObject(4, session.applicationId());
                statement.setObject(5, tenant);
                statement.setObject(6, authorized.membership());
                statement.setObject(7, branch);
                statement.setTimestamp(8, java.sql.Timestamp.from(session.expiresAt()));
                statement.setQueryTimeout(5);
                require(statement.executeUpdate() == 1);
            }
            return view(connection, session, id, authorized, session.expiresAt());
        });
    }

    public Context current(LoginApplication application, String token) {
        return sessions.withSession(application, token, (connection, session) -> {
            try (var statement = connection.prepareStatement("SELECT id,tenant_id,branch_id,membership_id,"
                    + "expires_at>clock_timestamp(),expires_at FROM security.session_contexts "
                    + "WHERE session_id=? AND user_id=? AND application_id=? AND revoked_at IS NULL")) {
                statement.setObject(1, session.id());
                statement.setObject(2, session.userId());
                statement.setObject(3, session.applicationId());
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    UUID id = rows.getObject(1, UUID.class);
                    var available = access(connection, session, rows.getObject(2, UUID.class), rows.getObject(3, UUID.class));
                    require(rows.getBoolean(5) && available.size() == 1
                            && available.getFirst().membership().equals(rows.getObject(4, UUID.class)));
                    var expiry = rows.getTimestamp(6).toInstant();
                    require(!rows.next());
                    return view(connection, session, id, available.getFirst(), expiry);
                }
            }
        });
    }

    private static List<Access> access(Connection connection, JdbcApplicationSessions.Session session,
            UUID tenant, UUID branch) throws SQLException {
        var result = new ArrayList<Access>();
        try (var statement = connection.prepareStatement(ACCESS + (tenant == null ? "" : "AND t.id=? ")
                + (branch == null ? "" : "AND b.id=? ") + "ORDER BY t.id,b.id")) {
            statement.setObject(1, session.userId());
            statement.setObject(2, session.applicationId());
            if (tenant != null) {
                statement.setObject(3, tenant);
            }
            if (branch != null) {
                statement.setObject(4, branch);
            }
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (TenantContextPolicy.eligible(rows.getString(8), rows.getString(9), rows.getString(10),
                            rows.getString(11), rows.getString(12), rows.getString(13), rows.getString(14))) {
                        result.add(new Access(new Tenant(rows.getObject(1, UUID.class), rows.getString(2)),
                                new Branch(rows.getObject(3, UUID.class), rows.getString(4), rows.getString(5)),
                                rows.getObject(6, UUID.class), rows.getObject(7, UUID.class)));
                    }
                }
            }
        }
        return result;
    }

    private static Context view(Connection connection, JdbcApplicationSessions.Session session, UUID id, Access access,
            java.time.Instant expiry) throws SQLException {
        var scope = new Scope(access.tenant().id(), session.applicationId(), access.branch().id());
        validateRelationships(session, id, scope, access, expiry);
        var permissions = new ArrayList<Permission>();
        var modules = new LinkedHashMap<UUID, Module>();
        try (var statement = connection.prepareStatement(PERMISSIONS)) {
            statement.setObject(1, scope.tenantId());
            statement.setObject(2, scope.applicationId());
            statement.setObject(3, access.branchMembership());
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var permission = new Permission(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getObject(5, UUID.class));
                    permissions.add(permission);
                    if (permission.moduleId() != null) {
                        var module = modules.get(permission.moduleId());
                        if (module == null) {
                            module = new Module(permission.moduleId(), rows.getString(6), rows.getString(7), new ArrayList<>());
                            modules.put(module.id(), module);
                        }
                        module.permissions().add(permission);
                    }
                }
            }
        }
        return new Context(id, scope.tenantId(), scope.applicationId(), scope.branchId(), List.copyOf(permissions),
                modules.values().stream().map(module -> new Module(module.id(), module.code(), module.name(),
                        List.copyOf(module.permissions()))).toList());
    }

    private static void validateRelationships(JdbcApplicationSessions.Session session, UUID id, Scope scope, Access access,
            java.time.Instant expiry) {
        // Use the existing resolver only inside this bounded snapshot; never publish a source bean,
        // reusable identity, claims or AuthorizedContext to the HTTP/business execution layer.
        var identity = new TrustedContextSource.AuthenticatedIdentity() { };
        var evidence = new TrustedContextSource.SessionEvidence(
                new TrustedContextSource.Session(session.id(), session.userId(), session.applicationId(), session.expiresAt(), null),
                new TrustedContextSource.SessionContext(id, session.id(), session.userId(), scope, access.membership(), expiry, null),
                new TrustedContextSource.Membership(access.membership(), scope.tenantId(), session.userId()),
                new TrustedContextSource.Branch(scope.branchId(), scope.tenantId()),
                new TrustedContextSource.BranchMembership(access.branchMembership(), scope.tenantId(), access.membership(), scope.branchId()),
                new TrustedContextSource.Entitlement(scope.tenantId(), scope.applicationId()), TrustedContextSource.Validity.VERIFIED);
        var source = new TrustedContextSource() {
            @Override
            public SessionEvidence session(AuthenticatedIdentity candidate, UUID selected) {
                require(candidate == identity && id.equals(selected));
                return evidence;
            }

            @Override
            public ApiKeyEvidence apiKey(AuthenticatedIdentity candidate) {
                throw new Denied();
            }
        };
        new ContextResolver(source, Clock.systemUTC()).session(identity, id, scope);
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new Denied();
        }
    }

    static final class Denied extends RuntimeException { }
}
