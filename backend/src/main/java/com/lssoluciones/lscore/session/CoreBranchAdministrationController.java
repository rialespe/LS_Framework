package com.lssoluciones.lscore.session;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Separate namespace from SessionContextController's operational branch selectors. */
@RestController
@RequestMapping("/api/auth/core/branch-administration")
@ConditionalOnProperty(name = "ls.core.branches.enabled", havingValue = "true")
public class CoreBranchAdministrationController {
    private final JdbcBranchAdministration branches;
    public CoreBranchAdministrationController(JdbcBranchAdministration branches) { this.branches = branches; }
    public record Request(UUID requestId, String code, String name, Instant expectedUpdatedAt, char[] password) {
        @Override public String toString() { return "BranchRequest[REDACTED]"; }
    }
    @GetMapping("/tenants")
    public Object companies(HttpServletRequest http) { return branches.companies(token(http)); }
    @GetMapping("/tenants/{tenant}/branches")
    public ResponseEntity<?> list(@PathVariable UUID tenant, @RequestParam(defaultValue = "0") int page, HttpServletRequest http) {
        if (page < 0 || page > 100000) { return invalid(); }
        return ResponseEntity.ok(branches.list(token(http), tenant, page));
    }
    @GetMapping("/tenants/{tenant}/branches/{id}")
    public ResponseEntity<?> detail(@PathVariable UUID tenant, @PathVariable UUID id, HttpServletRequest http) { return found(branches.detail(token(http), tenant, id)); }
    @GetMapping("/operations/{id}")
    public ResponseEntity<?> find(@PathVariable UUID id, HttpServletRequest http) { return found(branches.find(token(http), id)); }
    @PostMapping("/tenants/{tenant}/branches")
    public ResponseEntity<?> create(@PathVariable UUID tenant, @RequestBody Request request, HttpServletRequest http) {
        return mutate(tenant, null, JdbcBranchAdministration.Action.CREATE, request, http);
    }
    @PostMapping("/tenants/{tenant}/branches/{id}/{action:edit|suspend|reactivate|close}")
    public ResponseEntity<?> change(@PathVariable UUID tenant, @PathVariable UUID id, @PathVariable String action, @RequestBody Request request, HttpServletRequest http) {
        return mutate(tenant, id, JdbcBranchAdministration.Action.valueOf(action.toUpperCase(Locale.ROOT)), request, http);
    }
    private ResponseEntity<?> mutate(UUID tenant, UUID id, JdbcBranchAdministration.Action action, Request request, HttpServletRequest http) {
        try {
            if (request.requestId() == null) { return invalid(); }
            var result = branches.mutate(token(http), tenant, request.requestId(), action, id, request.code(), request.name(), request.expectedUpdatedAt(), request.password());
            return ResponseEntity.status(switch (result.outcome()) {
                case "CONFIRMED" -> action == JdbcBranchAdministration.Action.CREATE ? 201 : 200;
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
    @ExceptionHandler(JdbcBranchAdministration.Denied.class)
    ResponseEntity<?> denied() { return ResponseEntity.status(403).body(Map.of("error", "Operation unavailable")); }
    @ExceptionHandler(JdbcBranchAdministration.Unavailable.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("error", "Service unavailable")); }
    @ExceptionHandler(JdbcBranchAdministration.Missing.class)
    ResponseEntity<?> missing() { return ResponseEntity.status(404).body(Map.of("error", "Resource unavailable")); }
    @ExceptionHandler({HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid request")); }
}
