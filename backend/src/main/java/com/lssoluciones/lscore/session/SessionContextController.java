package com.lssoluciones.lscore.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Uses V16 cookies and CSRF; responses are contextual information, never SQL authority. */
@RestController
@RequestMapping("/api/auth/{application}")
@ConditionalOnProperty(name = {"ls.login.enabled", "ls.login.contexts.enabled"}, havingValue = "true")
public class SessionContextController {
    private final JdbcSessionContexts contexts;

    public SessionContextController(JdbcSessionContexts contexts) {
        this.contexts = contexts;
    }

    public record Selection(UUID tenantId, UUID branchId) { }

    @GetMapping("/tenants")
    public List<JdbcSessionContexts.Tenant> tenants(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            HttpServletRequest request) {
        return contexts.tenants(application, ApplicationLoginController.token(application, request));
    }

    @GetMapping("/tenants/{tenant}/branches")
    public List<JdbcSessionContexts.Branch> branches(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            @PathVariable UUID tenant, HttpServletRequest request) {
        return contexts.branches(application, ApplicationLoginController.token(application, request), tenant);
    }

    @PostMapping(path = "/context", consumes = "application/json")
    public JdbcSessionContexts.Context select(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            @RequestBody Selection selection, HttpServletRequest request) {
        return contexts.select(application, ApplicationLoginController.token(application, request), selection.tenantId(), selection.branchId());
    }

    @GetMapping("/context")
    public ResponseEntity<JdbcSessionContexts.Context> current(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            HttpServletRequest request) {
        var context = contexts.current(application, ApplicationLoginController.token(application, request));
        return context == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(context);
    }

    @GetMapping("/permissions")
    public List<JdbcSessionContexts.Permission> permissions(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            HttpServletRequest request) {
        return selected(application, request).permissions();
    }

    @GetMapping("/navigation")
    public List<JdbcSessionContexts.Module> navigation(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            HttpServletRequest request) {
        return selected(application, request).modules();
    }

    private JdbcSessionContexts.Context selected(LoginApplication application, HttpServletRequest request) {
        var context = contexts.current(application, ApplicationLoginController.token(application, request));
        if (context == null) {
            throw new JdbcSessionContexts.Denied();
        }
        return context;
    }

    @ExceptionHandler(JdbcApplicationSessions.Rejected.class)
    public ResponseEntity<Map<String, String>> unauthenticated() {
        return ResponseEntity.status(401).body(Map.of("error", "Authentication unavailable"));
    }

    @ExceptionHandler(JdbcSessionContexts.Denied.class)
    public ResponseEntity<Map<String, String>> denied() {
        return ResponseEntity.status(403).body(Map.of("error", "Context unavailable"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> malformed() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }
}
