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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bounded administrative-change history, independent from operational context selection. */
@RestController
@ConditionalOnProperty(name = "ls.core.branch-history.enabled", havingValue = "true")
public class CoreBranchHistoryController {
    private final JdbcBranchHistory history;
    public CoreBranchHistoryController(JdbcBranchHistory history) { this.history = history; }
    @GetMapping("/api/auth/core/branch-administration/tenants/{tenant}/historical-changes")
    public ResponseEntity<?> read(@PathVariable UUID tenant, @RequestParam List<UUID> branches,
            @RequestParam(defaultValue = "0") int page, HttpServletRequest http) {
        if (page < 0 || page > 100000 || branches.isEmpty() || branches.size() > 20 || branches.stream().distinct().count() != branches.size()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid selection"));
        }
        return ResponseEntity.ok(history.read(ApplicationLoginController.token(LoginApplication.CORE, http), tenant, branches, page));
    }
    @ExceptionHandler(JdbcApplicationSessions.Rejected.class)
    ResponseEntity<?> expired() { return ResponseEntity.status(401).body(Map.of("error", "Authentication unavailable")); }
    @ExceptionHandler(JdbcBranchAdministration.Denied.class)
    ResponseEntity<?> denied() { return ResponseEntity.status(403).body(Map.of("error", "Historical authority unavailable")); }
    @ExceptionHandler(JdbcBranchAdministration.Unavailable.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("error", "Service unavailable")); }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid selection")); }
}
