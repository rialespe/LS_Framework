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
@ConditionalOnProperty(name = "ls.core.identity-links.enabled", havingValue = "true")
public class CoreIdentityLinksController {
    private final JdbcIdentityLinks links;

    public CoreIdentityLinksController(JdbcIdentityLinks links) {
        this.links = links;
    }

    public record Request(UUID requestId, String username, char[] password) {
        @Override
        public String toString() {
            return "IdentityLinkRequest[REDACTED]";
        }
    }

    @GetMapping("/api/auth/core/identity-links/received")
    public Object received(@RequestParam(defaultValue = "0") int page, HttpServletRequest http) {
        return links.received(token(http), page);
    }

    @GetMapping("/api/auth/core/identity-links/tenants/{tenant}/sent")
    public Object sent(@PathVariable UUID tenant, @RequestParam(defaultValue = "0") int page, HttpServletRequest http) {
        return links.sent(token(http), tenant, page);
    }

    @GetMapping("/api/auth/core/identity-links/operations/{id}")
    public ResponseEntity<?> find(@PathVariable UUID id, HttpServletRequest http) {
        var value = links.find(token(http), id);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }

    @PostMapping("/api/auth/core/identity-links/tenants/{tenant}/requests")
    public ResponseEntity<?> request(@PathVariable UUID tenant, @RequestBody Request input, HttpServletRequest http) {
        return mutate(tenant, null, JdbcIdentityLinks.Action.REQUEST, input, http);
    }

    @PostMapping("/api/auth/core/identity-links/received/{id}/{action:accept|reject}")
    public ResponseEntity<?> decide(@PathVariable UUID id, @PathVariable String action,
            @RequestBody Request input, HttpServletRequest http) {
        return mutate(null, id, "accept".equals(action) ? JdbcIdentityLinks.Action.ACCEPT
                : JdbcIdentityLinks.Action.REJECT, input, http);
    }

    private ResponseEntity<?> mutate(UUID tenant, UUID link, JdbcIdentityLinks.Action action,
            Request input, HttpServletRequest http) {
        try {
            var result = links.mutate(token(http), tenant, input.requestId(), action, link, input.username(), input.password());
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
