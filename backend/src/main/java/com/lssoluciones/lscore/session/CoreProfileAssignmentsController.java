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
@ConditionalOnProperty(name = "ls.core.profile-assignments.enabled", havingValue = "true")
public class CoreProfileAssignmentsController {
    private final JdbcProfileAssignments links;

    public CoreProfileAssignmentsController(JdbcProfileAssignments links) {
        this.links = links;
    }

    public record Request(
            UUID requestId,
            UUID membershipId,
            UUID branchId,
            UUID branchMembershipId,
            UUID assignmentId,
            java.time.Instant expectedUpdatedAt,
            Long expectedRevision,
            java.util.List<UUID> profileIds,
            char[] password) {
        @Override
        public String toString() {
            return "ProfileAssignmentRequest[REDACTED]";
        }
    }

    @GetMapping(
            "/api/auth/core/rbac/assignments/tenants/{tenant}/applications/{application}/members")
    public Object members(
            @PathVariable UUID tenant, @PathVariable UUID application, HttpServletRequest http) {
        return links.members(token(http), tenant, application);
    }

    @GetMapping(
            "/api/auth/core/rbac/assignments/tenants/{tenant}/applications/{application}/members/{member}")
    public Object workspace(
            @PathVariable UUID tenant,
            @PathVariable UUID application,
            @PathVariable UUID member,
            HttpServletRequest http) {
        return links.workspace(token(http), tenant, application, member);
    }

    @GetMapping(
            "/api/auth/core/rbac/assignments/tenants/{tenant}/applications/{application}/members/{member}/branches/{branchMember}/preview")
    public Object preview(
            @PathVariable UUID tenant,
            @PathVariable UUID application,
            @PathVariable UUID member,
            @PathVariable UUID branchMember,
            HttpServletRequest http) {
        return links.preview(token(http), tenant, application, member, branchMember);
    }

    @GetMapping("/api/auth/core/rbac/assignments/operations/{id}")
    public ResponseEntity<?> find(@PathVariable UUID id, HttpServletRequest http) {
        var value = links.find(token(http), id);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }

    @PostMapping(
            "/api/auth/core/rbac/assignments/tenants/{tenant}/applications/{application}/commands/{action:join-branch|assign|revoke}")
    public ResponseEntity<?> mutate(
            @PathVariable UUID tenant,
            @PathVariable UUID application,
            @PathVariable String action,
            @RequestBody Request input,
            HttpServletRequest http) {
        try {
            JdbcProfileAssignments.Input command;
            try {
                command =
                        new JdbcProfileAssignments.Input(
                                input.membershipId(),
                                input.branchId(),
                                input.branchMembershipId(),
                                input.assignmentId(),
                                input.expectedUpdatedAt(),
                                input.expectedRevision(),
                                input.profileIds());
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new JdbcTenantUsers.Invalid();
            }
            var result =
                    links.mutate(
                            token(http),
                            tenant,
                            application,
                            input.requestId(),
                            JdbcProfileAssignments.Action.valueOf(
                                    action.replace('-', '_').toUpperCase(java.util.Locale.ROOT)),
                            command,
                            input.password());
            int status =
                    switch (result.outcome()) {
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

    @ExceptionHandler({
        JdbcTenantUsers.Invalid.class,
        HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }
}
