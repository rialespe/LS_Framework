package com.lssoluciones.lscore.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Locale;
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

@RestController
@ConditionalOnProperty(name = "ls.core.administrators.enabled", havingValue = "true")
public class CoreAdministratorsController {
    private final JdbcTenantAdministrators administrators;
    public CoreAdministratorsController(JdbcTenantAdministrators administrators) { this.administrators = administrators; }
    public record Request(UUID requestId, String username, char[] password) {
        @Override public String toString() { return "AdministratorRequest[REDACTED]"; }
    }
    public record CandidateRequest(String username) {
        @Override public String toString() { return "CandidateRequest[REDACTED]"; }
    }
    @GetMapping("/api/auth/core/administrator-tenants")
    public Object companies(HttpServletRequest http) { return administrators.companies(token(http)); }
    @GetMapping("/api/auth/core/tenants/{tenant}/administrators")
    public Object list(@PathVariable UUID tenant, HttpServletRequest http) { return administrators.list(token(http), tenant); }
    @GetMapping("/api/auth/core/tenants/{tenant}/administrators/{id}")
    public ResponseEntity<?> detail(@PathVariable UUID tenant, @PathVariable UUID id, HttpServletRequest http) { return found(administrators.detail(token(http), tenant, id)); }
    @PostMapping("/api/auth/core/tenants/{tenant}/administrators/candidate")
    public ResponseEntity<?> candidate(@PathVariable UUID tenant, @RequestBody CandidateRequest request, HttpServletRequest http) {
        return found(administrators.candidate(token(http), tenant, request.username()));
    }
    @GetMapping("/api/auth/core/administrator-operations/{id}")
    public ResponseEntity<?> find(@PathVariable UUID id, HttpServletRequest http) { return found(administrators.find(token(http), id)); }
    @PostMapping("/api/auth/core/tenants/{tenant}/administrators/designate")
    public ResponseEntity<?> designate(@PathVariable UUID tenant, @RequestBody Request request, HttpServletRequest http) {
        return mutate(tenant, null, JdbcTenantAdministrators.Action.DESIGNATE, request, http);
    }
    @PostMapping("/api/auth/core/tenants/{tenant}/administrators/{id}/{action:activate|revoke|replace}")
    public ResponseEntity<?> change(@PathVariable UUID tenant, @PathVariable UUID id, @PathVariable String action, @RequestBody Request request, HttpServletRequest http) {
        return mutate(tenant, id, JdbcTenantAdministrators.Action.valueOf(action.toUpperCase(Locale.ROOT)), request, http);
    }
    private ResponseEntity<?> mutate(UUID tenant, UUID target, JdbcTenantAdministrators.Action action, Request request, HttpServletRequest http) {
        try {
            var result = administrators.mutate(token(http), tenant, request.requestId(), action, target, request.username(), request.password());
            return ResponseEntity.status(switch (result.outcome()) {
                case "CONFIRMED" -> 200;
                case "UNKNOWN" -> 202;
                case "DENIED" -> 403;
                case "NOT_FOUND" -> 404;
                case "CONFLICT" -> 409;
                case "INCOMPLETE_REQUIREMENTS" -> 422;
                default -> 503;
            }).body(result);
        } finally { if (request.password() != null) { Arrays.fill(request.password(), '\0'); } }
    }
    private static ResponseEntity<?> found(Object value) { return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value); }
    private static String token(HttpServletRequest http) { return ApplicationLoginController.token(LoginApplication.CORE, http); }
    @ExceptionHandler(JdbcApplicationSessions.Rejected.class)
    ResponseEntity<?> expired() { return ResponseEntity.status(401).body(Map.of("error", "Authentication unavailable")); }
    @ExceptionHandler(JdbcTenantAdministrators.Denied.class)
    ResponseEntity<?> denied() { return ResponseEntity.status(403).body(Map.of("error", "Operation unavailable")); }
    @ExceptionHandler(JdbcTenantAdministrators.Unavailable.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("error", "Service unavailable")); }
    @ExceptionHandler(JdbcTenantAdministrators.Missing.class)
    ResponseEntity<?> missing() { return ResponseEntity.status(404).body(Map.of("error", "Resource unavailable")); }
    @ExceptionHandler({HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid request")); }
}
