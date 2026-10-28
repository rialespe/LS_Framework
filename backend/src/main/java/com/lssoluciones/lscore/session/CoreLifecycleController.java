package com.lssoluciones.lscore.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@ConditionalOnProperty(name = "ls.core.lifecycle.enabled", havingValue = "true")
public class CoreLifecycleController {
    private final JdbcTenantLifecycle lifecycle;
    public CoreLifecycleController(JdbcTenantLifecycle lifecycle) { this.lifecycle = lifecycle; }
    public record Request(UUID requestId, char[] password) {
        @Override public String toString() { return "LifecycleRequest[REDACTED]"; }
    }
    @GetMapping("/api/auth/core/tenant-authority")
    public JdbcTenantLifecycle.Authority authority(HttpServletRequest http) { return lifecycle.authority(token(http)); }
    @GetMapping("/api/auth/core/tenants")
    public Object list(@RequestParam(defaultValue = "0") int page, HttpServletRequest http) { return lifecycle.list(token(http), page); }
    @GetMapping("/api/auth/core/tenants/{id}")
    public ResponseEntity<?> detail(@PathVariable UUID id, HttpServletRequest http) {
        var value = lifecycle.detail(token(http), id);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }
    @GetMapping("/api/auth/core/tenant-operations/{id}")
    public ResponseEntity<?> find(@PathVariable UUID id, HttpServletRequest http) {
        var value = lifecycle.find(token(http), id);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }
    @PostMapping("/api/auth/core/tenants/{id}/{action:activate|suspend|reactivate}")
    public ResponseEntity<?> transition(@PathVariable UUID id, @PathVariable String action, @RequestBody Request request, HttpServletRequest http) {
        try {
            var result = lifecycle.transition(token(http), id, request.requestId(), JdbcTenantLifecycle.Action.valueOf(action.toUpperCase(java.util.Locale.ROOT)), request.password());
            int status = switch (result.outcome()) {
                case "CONFIRMED" -> 200;
                case "UNKNOWN" -> 202;
                case "DENIED" -> 403;
                case "INVALID_TRANSITION" -> 409;
                case "INCOMPLETE_REQUIREMENTS" -> 422;
                default -> 503;
            };
            return ResponseEntity.status(status).body(result);
        } finally {
            if (request.password() != null) { Arrays.fill(request.password(), '\0'); }
        }
    }
    private static String token(HttpServletRequest http) { return ApplicationLoginController.token(LoginApplication.CORE, http); }
    @ExceptionHandler(JdbcApplicationSessions.Rejected.class)
    ResponseEntity<?> expired() { return ResponseEntity.status(401).body(Map.of("error", "Authentication unavailable")); }
    @ExceptionHandler(JdbcTenantLifecycle.Unavailable.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("error", "Service unavailable")); }
    @ExceptionHandler(JdbcTenantLifecycle.Denied.class)
    ResponseEntity<?> denied() { return ResponseEntity.status(403).body(Map.of("error", "Operation unavailable")); }
    @ExceptionHandler({HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid request")); }
}
