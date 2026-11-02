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
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "ls.core.tenant-access.enabled", havingValue = "true")
public class CoreTenantAccessController {
    private final JdbcTenantAccess links;

    public CoreTenantAccessController(JdbcTenantAccess links) {
        this.links = links;
    }

    public record Request(UUID requestId, java.time.Instant expectedUpdatedAt, char[] password) {
        @Override
        public String toString() {
            return "TenantAccessRequest[REDACTED]";
        }
    }

    @GetMapping("/api/auth/core/tenant-users/tenants/{tenant}/users/{id}/access")
    public Object access(@PathVariable UUID tenant, @PathVariable UUID id, HttpServletRequest http) {
        return links.access(token(http), tenant, id);
    }

    @GetMapping("/api/auth/core/tenant-users/access-operations/{id}")
    public ResponseEntity<?> find(@PathVariable UUID id, HttpServletRequest http) {
        var value = links.find(token(http), id);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }

    @PostMapping("/api/auth/core/tenant-users/tenants/{tenant}/users/{id}/access/{action:suspend|reactivate}")
    public ResponseEntity<?> member(@PathVariable UUID tenant, @PathVariable UUID id, @PathVariable String action,
            @RequestBody Request input, HttpServletRequest http) {
        return mutate(tenant, id, null, "suspend".equals(action) ? JdbcTenantAccess.Action.MEMBER_SUSPEND
                : JdbcTenantAccess.Action.MEMBER_REACTIVATE, input, http);
    }

    @PostMapping("/api/auth/core/tenant-users/tenants/{tenant}/users/{id}/branch-accesses/{branch}/{action:suspend|reactivate}")
    public ResponseEntity<?> branch(@PathVariable UUID tenant, @PathVariable UUID id, @PathVariable UUID branch,
            @PathVariable String action, @RequestBody Request input, HttpServletRequest http) {
        return mutate(tenant, id, branch, "suspend".equals(action) ? JdbcTenantAccess.Action.BRANCH_SUSPEND
                : JdbcTenantAccess.Action.BRANCH_REACTIVATE, input, http);
    }

    private ResponseEntity<?> mutate(UUID tenant, UUID member, UUID branch, JdbcTenantAccess.Action action,
            Request input, HttpServletRequest http) {
        try {
            var result = links.mutate(token(http), tenant, input.requestId(), action, member, branch, input.expectedUpdatedAt(), input.password());
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
