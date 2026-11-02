package com.lssoluciones.lscore.session;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Own context projection only. Business consumers must revalidate within their transaction. */
@RestController
@ConditionalOnProperty(name = "ls.core.profile-assignments.enabled", havingValue = "true")
public class CoreContextPermissionsController {
    private final JdbcSessionContexts contexts;

    public CoreContextPermissionsController(JdbcSessionContexts contexts) {
        this.contexts = contexts;
    }

    public record Selection(UUID tenantId, UUID branchId) {}

    @GetMapping("/api/auth/core/rbac/context/tenants")
    public Object tenants(HttpServletRequest request) {
        return contexts.tenants(LoginApplication.CORE, token(request));
    }

    @GetMapping("/api/auth/core/rbac/context/tenants/{tenant}/branches")
    public Object branches(@PathVariable UUID tenant, HttpServletRequest request) {
        return contexts.branches(LoginApplication.CORE, token(request), tenant);
    }

    @PostMapping("/api/auth/core/rbac/context")
    public Object select(@RequestBody Selection input, HttpServletRequest request) {
        return contexts.select(
                LoginApplication.CORE, token(request), input.tenantId(), input.branchId());
    }

    @GetMapping("/api/auth/core/rbac/context/permissions")
    public ResponseEntity<?> current(HttpServletRequest request) {
        var value = contexts.current(LoginApplication.CORE, token(request));
        return value == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(value);
    }

    private static String token(HttpServletRequest request) {
        return ApplicationLoginController.token(LoginApplication.CORE, request);
    }

    @ExceptionHandler(JdbcApplicationSessions.Rejected.class)
    ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(401).build();
    }

    @ExceptionHandler(JdbcSessionContexts.Denied.class)
    ResponseEntity<?> denied() {
        return ResponseEntity.status(403).build();
    }
}
