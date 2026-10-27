package com.lssoluciones.lscore.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Explicit login/session-status/logout only. Cookies are never accepted by F03 operation admission. */
@RestController
@ConditionalOnProperty(name = "ls.login.enabled", havingValue = "true")
public class ApplicationLoginController {
    private final JdbcApplicationSessions sessions;

    public ApplicationLoginController(JdbcApplicationSessions sessions) {
        this.sessions = sessions;
    }

    public record Credentials(String username, char[] password) {
        @Override
        public String toString() {
            return "Credentials[REDACTED]";
        }
    }

    @GetMapping("/api/auth/{application}/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Map.of("csrfToken", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping(path = "/api/auth/{application}/login", consumes = "application/json")
    public ResponseEntity<Void> login(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            @RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
        try {
            String token = sessions.login(application, credentials.username(), credentials.password());
            rotateCsrf(request, response);
            return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,
                    cookie(application, token, request, Duration.ofSeconds(sessions.validitySeconds()))).build();
        } finally {
            if (credentials.password() != null) {
                Arrays.fill(credentials.password(), '\0');
            }
        }
    }

    @GetMapping("/api/auth/{application}/session")
    public Map<String, Boolean> session(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            HttpServletRequest request) {
        sessions.requireSession(application, token(application, request));
        return Map.of("authenticated", true);
    }

    @PostMapping("/api/auth/{application}/logout")
    public ResponseEntity<Void> logout(@RequestAttribute(ApplicationLoginFilter.APPLICATION) LoginApplication application,
            HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(application, token(application, request));
        rotateCsrf(request, response);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie(application, "", request, Duration.ZERO)).build();
    }

    private static String cookie(LoginApplication application, String token, HttpServletRequest request, Duration age) {
        return ResponseCookie.from(application.sessionCookie(), token).secure(true).httpOnly(true).sameSite("Strict")
                .path(request.getContextPath() + application.path()).maxAge(age).build().toString();
    }

    private static String token(LoginApplication application, HttpServletRequest request) {
        String token = null;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (application.sessionCookie().equals(cookie.getName())) {
                    if (token != null) {
                        throw new InvalidSession();
                    }
                    token = cookie.getValue();
                }
            }
        }
        return token;
    }

    private static void rotateCsrf(HttpServletRequest request, HttpServletResponse response) {
        ((CsrfTokenRepository) request.getAttribute(ApplicationLoginFilter.REPOSITORY)).saveToken(null, request, response);
    }

    @ExceptionHandler({JdbcApplicationSessions.Rejected.class, InvalidSession.class})
    public ResponseEntity<Map<String, String>> rejected() {
        return ResponseEntity.status(401).body(Map.of("error", "Authentication unavailable"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }

    private static final class InvalidSession extends RuntimeException { }
}
