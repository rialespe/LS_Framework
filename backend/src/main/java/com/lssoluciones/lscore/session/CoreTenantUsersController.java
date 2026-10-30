package com.lssoluciones.lscore.session;

import com.lssoluciones.lscore.identity.application.ManualIdentityInput;

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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "ls.core.tenant-users.enabled", havingValue = "true")
public class CoreTenantUsersController {
    private final JdbcTenantUsers users;
    private final ObjectMapper mapper;
    private final com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle
            lifecycle;

    public CoreTenantUsersController(
            JdbcTenantUsers users,
            ObjectMapper mapper,
            com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle lifecycle) {
        this.users = users;
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    public record Request(
            UUID requestId, String username, String displayName, String email, char[] password) {
        @Override
        public String toString() {
            return "TenantUserRequest[REDACTED]";
        }
    }

    public record Exchange(String username, char[] temporaryPassword, char[] newPassword) {
        @Override
        public String toString() {
            return "Exchange[REDACTED]";
        }
    }

    @PostMapping("/api/auth/core/tenant-users/exchange")
    public ResponseEntity<?> exchange(@RequestBody Exchange request) {
        try {
            lifecycle.exchange(
                    request.username(), request.temporaryPassword(), request.newPassword());
            return ResponseEntity.noContent().build();
        } catch (
                com.lssoluciones.lscore.identity.infrastructure.jdbc.JdbcPasswordLifecycle
                                .PasswordChangeRejectedException
                        failure) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", "Password operation unavailable"));
        } finally {
            if (request.temporaryPassword() != null) {
                Arrays.fill(request.temporaryPassword(), '\0');
            }
            if (request.newPassword() != null) {
                Arrays.fill(request.newPassword(), '\0');
            }
        }
    }

    @GetMapping("/api/auth/core/tenant-users/tenants")
    public Object companies(HttpServletRequest http) {
        return users.companies(token(http));
    }

    @GetMapping("/api/auth/core/tenant-users/tenants/{tenant}/users")
    public Object list(
            @PathVariable UUID tenant,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest http) {
        return users.list(token(http), tenant, page);
    }

    @GetMapping("/api/auth/core/tenant-users/tenants/{tenant}/users/{id}")
    public ResponseEntity<?> detail(
            @PathVariable UUID tenant, @PathVariable UUID id, HttpServletRequest http) {
        return found(users.detail(token(http), tenant, id));
    }

    @GetMapping("/api/auth/core/tenant-users/operations/{id}")
    public ResponseEntity<?> find(@PathVariable UUID id, HttpServletRequest http) {
        return found(users.find(token(http), id));
    }

    @PostMapping("/api/auth/core/tenant-users/tenants/{tenant}/users")
    public ResponseEntity<StreamingResponseBody> create(
            @PathVariable UUID tenant, @RequestBody Request request, HttpServletRequest http) {
        return mutate(tenant, null, JdbcTenantUsers.Action.CREATE, request, http);
    }

    @PostMapping(
            "/api/auth/core/tenant-users/tenants/{tenant}/users/{id}/{action:activate|reissue}")
    public ResponseEntity<StreamingResponseBody> change(
            @PathVariable UUID tenant,
            @PathVariable UUID id,
            @PathVariable String action,
            @RequestBody Request request,
            HttpServletRequest http) {
        return mutate(
                tenant,
                id,
                "activate".equals(action)
                        ? JdbcTenantUsers.Action.ACTIVATE
                        : JdbcTenantUsers.Action.REISSUE,
                request,
                http);
    }

    private ResponseEntity<StreamingResponseBody> mutate(
            UUID tenant,
            UUID id,
            JdbcTenantUsers.Action action,
            Request request,
            HttpServletRequest http) {
        try {
            ManualIdentityInput input = null;
            if (action == JdbcTenantUsers.Action.CREATE) {
                try {
                    input =
                            new ManualIdentityInput(
                                    request.username(), request.displayName(), request.email());
                } catch (IllegalArgumentException invalid) {
                    throw new JdbcTenantUsers.Invalid();
                }
            }
            var result =
                    users.mutate(
                            token(http),
                            tenant,
                            request.requestId(),
                            action,
                            id,
                            input,
                            request.password());
            int status =
                    switch (result.outcome()) {
                        case "CONFIRMED" -> action == JdbcTenantUsers.Action.CREATE ? 201 : 200;
                        case "UNKNOWN" -> 202;
                        case "DENIED" -> 403;
                        case "NOT_FOUND" -> 404;
                        case "CONFLICT" -> 409;
                        case "INCOMPLETE_REQUIREMENTS" -> 422;
                        case "INVALID" -> 400;
                        default -> 503;
                    };
            return ResponseEntity.status(status)
                    .header("Content-Type", "application/json")
                    .body(
                            output -> {
                                try (result) {
                                    mapper.writeValue(output, result);
                                }
                            });
        } finally {
            if (request.password() != null) {
                Arrays.fill(request.password(), '\0');
            }
        }
    }

    private static ResponseEntity<?> found(Object value) {
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
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
        return ResponseEntity.status(404).body(Map.of("error", "Resource unavailable"));
    }

    @ExceptionHandler(JdbcTenantUsers.Unavailable.class)
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("error", "Service unavailable"));
    }

    @ExceptionHandler({
        JdbcTenantUsers.Invalid.class,
        HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }
}
