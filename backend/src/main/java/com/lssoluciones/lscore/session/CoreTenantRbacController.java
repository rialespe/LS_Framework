package com.lssoluciones.lscore.session;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "ls.core.tenant-rbac.enabled", havingValue = "true")
public class CoreTenantRbacController {
    private final JdbcTenantRbac links;

    public CoreTenantRbacController(JdbcTenantRbac links) {
        this.links = links;
    }

    public record Request(UUID requestId, UUID resourceId, UUID templateId, Long expectedRevision,
            String code, String name, String description, java.util.List<UUID> permissionIds,
            java.util.List<UUID> roleIds, char[] password) {
        @Override
        public String toString() {
            return "TenantRbacRequest[REDACTED]";
        }
    }

    @GetMapping("/api/auth/core/rbac/tenants")
    public Object tenants(HttpServletRequest http) {
        return links.companies(token(http));
    }

    @GetMapping("/api/auth/core/rbac/tenants/{tenant}/applications")
    public Object applications(@PathVariable UUID tenant, HttpServletRequest http) {
        return links.applications(token(http), tenant);
    }

    @GetMapping("/api/auth/core/rbac/tenants/{tenant}/applications/{application}")
    public Object workspace(@PathVariable UUID tenant, @PathVariable UUID application, HttpServletRequest http) {
        return links.workspace(token(http), tenant, application);
    }

    @GetMapping("/api/auth/core/rbac/tenants/{tenant}/applications/{application}/{section:modules|permissions|role-templates|profile-templates|roles|profiles}")
    public Object section(@PathVariable UUID tenant, @PathVariable UUID application, @PathVariable String section, HttpServletRequest http) {
        var value = links.workspace(token(http), tenant, application);
        return switch (section) {
            case "modules" -> value.modules();
            case "permissions" -> value.permissions();
            case "role-templates" -> value.roleTemplates();
            case "profile-templates" -> value.profileTemplates();
            case "roles" -> value.roles();
            default -> value.profiles();
        };
    }

    @GetMapping("/api/auth/core/rbac/tenants/{tenant}/applications/{application}/{kind:roles|profiles}/{id}")
    public Object detail(@PathVariable UUID tenant, @PathVariable UUID application, @PathVariable String kind,
            @PathVariable UUID id, HttpServletRequest http) {
        return links.detail(token(http), tenant, application, "roles".equals(kind) ? "ROLE" : "PROFILE", id);
    }

    @GetMapping({"/api/auth/core/rbac/tenants/{tenant}/applications/{application}/roles/{id}/permissions",
            "/api/auth/core/rbac/tenants/{tenant}/applications/{application}/profiles/{id}/roles"})
    public Object composition(@PathVariable UUID tenant, @PathVariable UUID application, @PathVariable UUID id, HttpServletRequest http) {
        boolean role = http.getServletPath().endsWith("/permissions");
        return links.detail(token(http), tenant, application, role ? "ROLE" : "PROFILE", id).path(role ? "permissionIds" : "roleIds");
    }

    @GetMapping("/api/auth/core/rbac/tenants/{tenant}/applications/{application}/history")
    public Object history(@PathVariable UUID tenant, @PathVariable UUID application,
            @RequestParam(required = false) UUID resourceId, HttpServletRequest http) {
        return links.history(token(http), tenant, application, resourceId);
    }

    @GetMapping("/api/auth/core/rbac/operations/{id}")
    public ResponseEntity<?> find(@PathVariable UUID id, HttpServletRequest http) {
        var value = links.find(token(http), id);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }

    @PostMapping("/api/auth/core/rbac/tenants/{tenant}/applications/{application}/commands/{action:(?:role|profile)-(?:create|update|disable|template)}")
    public ResponseEntity<?> mutate(@PathVariable UUID tenant, @PathVariable UUID application,
            @PathVariable String action, @RequestBody Request input, HttpServletRequest http) {
        try {
            JdbcTenantRbac.Input command;
            try {
                command = new JdbcTenantRbac.Input(input.resourceId(), input.templateId(), input.expectedRevision(),
                        input.code(), input.name(), input.description(), input.permissionIds(), input.roleIds());
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new JdbcTenantUsers.Invalid();
            }
            var result = links.mutate(token(http), tenant, application, input.requestId(),
                    JdbcTenantRbac.Action.valueOf(action.replace('-', '_').toUpperCase(java.util.Locale.ROOT)), command, input.password());
            int status = switch (result.outcome()) {
                case "CONFIRMED" -> 200;
                case "UNKNOWN" -> 202;
                case "DENIED" -> 403;
                case "NOT_FOUND" -> 404;
                case "CONFLICT" -> 409;
                case "INCOMPLETE_REQUIREMENTS" -> 422;
                case "INVALID" -> 400;
                default -> 503;
            };
            return ResponseEntity.status(status).body(result);
        } finally {
            if (input.password() != null) {
                Arrays.fill(input.password(), '\0');
            }
        }
    }
    private static String token(HttpServletRequest http) {
        return ApplicationLoginController.token(LoginApplication.CORE, http);
    }

    @ExceptionHandler(JdbcApplicationSessions.Rejected.class)
    ResponseEntity<?> expired() {
        return ResponseEntity.status(401).body(Map.of("error", "Authentication unavailable"));
    }

    @ExceptionHandler(JdbcTenantUsers.Denied.class)
    ResponseEntity<?> denied() {
        return ResponseEntity.status(403).body(Map.of("error", "Operation unavailable"));
    }

    @ExceptionHandler(JdbcTenantUsers.Missing.class)
    ResponseEntity<?> missing() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(JdbcTenantUsers.Unavailable.class)
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("error", "Service unavailable"));
    }

    @ExceptionHandler({JdbcTenantUsers.Invalid.class, HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }
}
