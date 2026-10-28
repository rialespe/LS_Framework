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

@RestController
@ConditionalOnProperty(name = "ls.core.registration.enabled", havingValue = "true")
public class CoreRegistrationController {
    private final JdbcTenantRegistration registration;
    public CoreRegistrationController(JdbcTenantRegistration registration) { this.registration = registration; }
    public record Lookup(String username) { }
    public record Request(UUID requestId, String name, UUID administratorId, char[] password) {
        @Override public String toString() { return "RegistrationRequest[REDACTED]"; }
    }

    @GetMapping("/api/auth/core/authority")
    public JdbcTenantRegistration.Authority authority(HttpServletRequest http) { return registration.authority(token(http)); }

    @PostMapping("/api/auth/core/initial-administrator")
    public ResponseEntity<?> administrator(@RequestBody Lookup lookup, HttpServletRequest http) {
        var result = registration.administrator(token(http), lookup.username());
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @GetMapping("/api/auth/core/registrations/{requestId}")
    public ResponseEntity<?> find(@PathVariable UUID requestId, HttpServletRequest http) {
        var result = registration.find(token(http), requestId);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(new JdbcTenantRegistration.Result("CONFIRMED", requestId, result));
    }

    @PostMapping("/api/auth/core/registrations")
    public ResponseEntity<?> create(@RequestBody Request request, HttpServletRequest http) {
        try {
            var result = registration.register(token(http), request.requestId(), request.name(), request.administratorId(), request.password());
            int status = switch (result.outcome()) {
                case "CONFIRMED" -> 200;
                case "UNKNOWN" -> 202;
                case "DENIED" -> 403;
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
    @ExceptionHandler(JdbcTenantRegistration.Denied.class)
    ResponseEntity<?> denied() { return ResponseEntity.status(403).body(Map.of("error", "Operation unavailable")); }
    @ExceptionHandler({HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid request")); }
}
