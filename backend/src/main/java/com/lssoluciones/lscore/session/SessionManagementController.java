package com.lssoluciones.lscore.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** V16 cookies/CSRF, same application only. No client-supplied user identity. */
@RestController
@RequestMapping("/api/auth/{application}")
@ConditionalOnProperty(name = {"ls.login.enabled", "ls.login.multidevice.enabled"}, havingValue = "true")
public class SessionManagementController {
    private final JdbcSessionManagement management;

    public SessionManagementController(JdbcSessionManagement management) {
        this.management = management;
    }

    @GetMapping("/sessions")
    public List<JdbcSessionManagement.Entry> list(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            HttpServletRequest request) {
        return management.list(application, ApplicationLoginController.token(application, request), null);
    }

    @PostMapping("/sessions/{id}/revoke")
    public Map<String, Integer> revoke(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            @PathVariable UUID id, HttpServletRequest request) {
        return Map.of("revoked", management.revoke(application, ApplicationLoginController.token(application, request), id, false));
    }

    @PostMapping("/sessions/revoke-others")
    public Map<String, Integer> revokeOthers(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            HttpServletRequest request) {
        return Map.of("revoked", management.revoke(application, ApplicationLoginController.token(application, request), null, true));
    }

    @GetMapping("/tenants/{tenant}/session-administration")
    public Map<String, Boolean> authority(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            @PathVariable UUID tenant, HttpServletRequest request) {
        return Map.of("authorized", management.authorized(application, ApplicationLoginController.token(application, request), tenant));
    }

    @GetMapping("/tenants/{tenant}/sessions")
    public List<JdbcSessionManagement.Entry> tenantSessions(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            @PathVariable UUID tenant, HttpServletRequest request) {
        return management.list(application, ApplicationLoginController.token(application, request), tenant);
    }

    @PostMapping("/tenants/{tenant}/sessions/{id}/revoke")
    public ResponseEntity<Void> revokeTenant(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            @PathVariable UUID tenant, @PathVariable UUID id, HttpServletRequest request) {
        management.revokeTenant(application, ApplicationLoginController.token(application, request), tenant, id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(JdbcApplicationSessions.Rejected.class)
    public ResponseEntity<Map<String, String>> unauthenticated() {
        return ResponseEntity.status(401).body(Map.of("error", "Authentication unavailable"));
    }

    @ExceptionHandler(JdbcSessionContexts.Denied.class)
    public ResponseEntity<Map<String, String>> denied() {
        return ResponseEntity.status(403).body(Map.of("error", "Session operation unavailable"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }
}
