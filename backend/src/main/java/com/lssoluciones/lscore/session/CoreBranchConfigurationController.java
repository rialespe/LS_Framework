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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/auth/core/branch-administration")
@ConditionalOnProperty(name = "ls.core.branch-configuration.enabled", havingValue = "true")
public class CoreBranchConfigurationController {
    private final JdbcBranchConfiguration configurations;
    public CoreBranchConfigurationController(JdbcBranchConfiguration configurations) { this.configurations = configurations; }
    public record Request(UUID requestId, Long expectedRevision, JsonNode values, char[] password) {
        @Override public String toString() { return "ConfigurationRequest[REDACTED]"; }
    }
    @GetMapping("/tenants/{tenant}/branches/{branch}/module-configurations")
    public Object modules(@PathVariable UUID tenant, @PathVariable UUID branch, HttpServletRequest http) { return configurations.modules(token(http), tenant, branch); }
    @GetMapping("/tenants/{tenant}/branches/{branch}/module-configurations/{application}/{module}/{version}")
    public Object detail(@PathVariable UUID tenant, @PathVariable UUID branch, @PathVariable UUID application, @PathVariable UUID module, @PathVariable int version, HttpServletRequest http) {
        return configurations.detail(token(http), new JdbcBranchConfiguration.Scope(tenant, branch, application, module, version));
    }
    @GetMapping("/configuration-operations/{id}")
    public ResponseEntity<?> find(@PathVariable UUID id, HttpServletRequest http) {
        var value = configurations.find(token(http), id);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }
    @PostMapping("/tenants/{tenant}/branches/{branch}/module-configurations/{application}/{module}/{version}/{action:prepare|update|validate}")
    public ResponseEntity<?> mutate(@PathVariable UUID tenant, @PathVariable UUID branch, @PathVariable UUID application, @PathVariable UUID module, @PathVariable int version,
            @PathVariable String action, @RequestBody Request request, HttpServletRequest http) {
        try {
            if (request.requestId() == null || version < 1 || version > 100000) { return invalid(); }
            var result = configurations.mutate(token(http), new JdbcBranchConfiguration.Scope(tenant, branch, application, module, version), request.requestId(),
                    JdbcBranchConfiguration.Action.valueOf(action.toUpperCase(Locale.ROOT)), request.expectedRevision(), request.values(), request.password());
            return ResponseEntity.status(switch (result.outcome()) {
                case "CONFIRMED" -> 200;
                case "UNKNOWN" -> 202;
                case "DENIED" -> 403;
                case "NOT_FOUND" -> 404;
                case "MODULE_UNAVAILABLE", "CONFLICT" -> 409;
                case "INCOMPLETE_REQUIREMENTS" -> 422;
                default -> 503;
            }).body(result);
        } finally { if (request.password() != null) { Arrays.fill(request.password(), '\0'); } }
    }
    private static String token(HttpServletRequest http) { return ApplicationLoginController.token(LoginApplication.CORE, http); }
    @ExceptionHandler(JdbcApplicationSessions.Rejected.class)
    ResponseEntity<?> expired() { return ResponseEntity.status(401).body(Map.of("error", "Authentication unavailable")); }
    @ExceptionHandler(JdbcBranchConfiguration.Denied.class)
    ResponseEntity<?> denied() { return ResponseEntity.status(403).body(Map.of("error", "Operation unavailable")); }
    @ExceptionHandler(JdbcBranchConfiguration.Unavailable.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("error", "Service unavailable")); }
    @ExceptionHandler(JdbcBranchConfiguration.Missing.class)
    ResponseEntity<?> missing() { return ResponseEntity.status(404).body(Map.of("error", "Resource unavailable")); }
    @ExceptionHandler({HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid request")); }
}
