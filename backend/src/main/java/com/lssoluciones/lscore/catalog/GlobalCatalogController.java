package com.lssoluciones.lscore.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Development-only public catalog transport; no enterprise identity or authorization. */
@RestController
@Profile("catalog-docker")
@ConditionalOnProperty(name = "ls.catalog.api.enabled", havingValue = "true")
public class GlobalCatalogController {
    private final GlobalCatalogReader reader;

    public GlobalCatalogController(GlobalCatalogReader reader, Environment environment) {
        if (!"127.0.0.1".equals(environment.getProperty("server.address"))
                || !"127.0.0.1".equals(environment.getProperty("LS_CATALOG_DB_HOST"))
                || !"55432".equals(environment.getProperty("LS_CATALOG_DB_PORT"))
                || !"ls_core_dev".equals(environment.getProperty("LS_CATALOG_DB_NAME"))
                || !environment.getProperty("ls.catalog.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Catalog API requires explicit local Docker configuration");
        }
        this.reader = reader;
    }

    @GetMapping("/api/global-catalog/applications")
    public ResponseEntity<List<GlobalCatalogReader.Application>> applications(
            @RequestParam(name = "limit", defaultValue = "100") String limit,
            @RequestParam(name = "offset", defaultValue = "0") String offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reader.applications(page(limit, 1, 100), page(offset, 0, 10000)));
    }

    @GetMapping("/api/global-catalog/applications/{applicationId}/modules")
    public ResponseEntity<List<GlobalCatalogReader.Module>> modules(@PathVariable("applicationId") String applicationId,
            @RequestParam(name = "limit", defaultValue = "100") String limit,
            @RequestParam(name = "offset", defaultValue = "0") String offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reader.modules(UUID.fromString(applicationId), page(limit, 1, 100), page(offset, 0, 10000)));
    }

    private static int page(String value, int minimum, int maximum) {
        int result = Integer.parseInt(value);
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException();
        }
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("error", "Invalid catalog request"));
    }

    @ExceptionHandler(GlobalCatalogReader.CatalogUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "Global catalog unavailable"));
    }
}
