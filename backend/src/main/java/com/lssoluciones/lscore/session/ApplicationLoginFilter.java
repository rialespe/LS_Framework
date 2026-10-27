package com.lssoluciones.lscore.session;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/** HTTPS-only, per-application framework CSRF. No HttpSession/JSESSIONID or business authentication is installed. */
final class ApplicationLoginFilter implements Filter {
    static final String APPLICATION = "ls.login.application";
    static final String REPOSITORY = "ls.login.csrf.repository";
    private final Map<LoginApplication, CookieCsrfTokenRepository> repositories = new EnumMap<>(LoginApplication.class);
    private final Map<LoginApplication, CsrfFilter> filters = new EnumMap<>(LoginApplication.class);

    ApplicationLoginFilter() {
        for (var application : LoginApplication.values()) {
            var repository = new CookieCsrfTokenRepository();
            repository.setCookieName(application.csrfCookie());
            repository.setCookiePath("/"); // __Host- requires / and forbids Domain; names remain app-specific.
            repository.setHeaderName("X-CSRF-TOKEN");
            repository.setCookieCustomizer(cookie -> cookie.secure(true).httpOnly(true).sameSite("Strict"));
            var csrf = new CsrfFilter(repository);
            csrf.setRequestHandler(new XorCsrfTokenRequestAttributeHandler());
            csrf.setAccessDeniedHandler((request, response, failure) -> reject(response, 403));
            repositories.put(application, repository);
            filters.put(application, csrf);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        var http = (HttpServletRequest) request;
        var output = (HttpServletResponse) response;
        output.setHeader("Cache-Control", "no-store");
        output.setHeader("Pragma", "no-cache");
        output.setHeader("X-Content-Type-Options", "nosniff");
        if (!http.isSecure() || http.getContentLengthLong() > 8192) {
            reject(output, 403);
            return;
        }
        String path = http.getServletPath();
        for (var application : LoginApplication.values()) {
            if (path.startsWith(application.path() + "/")) {
                http.setAttribute(APPLICATION, application);
                http.setAttribute(REPOSITORY, repositories.get(application));
                filters.get(application).doFilter(http, output, chain);
                return;
            }
        }
        reject(output, 404);
    }

    private static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Request rejected\"}");
    }
}
