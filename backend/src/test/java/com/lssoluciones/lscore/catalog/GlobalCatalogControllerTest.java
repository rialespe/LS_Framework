package com.lssoluciones.lscore.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GlobalCatalogControllerTest {
    private MockEnvironment local() {
        return new MockEnvironment().withProperty("server.address", "127.0.0.1")
                .withProperty("LS_CATALOG_DB_HOST", "127.0.0.1")
                .withProperty("LS_CATALOG_DB_PORT", "55432").withProperty("LS_CATALOG_DB_NAME", "ls_core_dev")
                .withProperty("ls.catalog.enabled", "true");
    }

    @Test
    void disabledByDefaultAndRequiresDevelopmentProfile() {
        var context = new ApplicationContextRunner().withUserConfiguration(GlobalCatalogController.class);
        context.run(app -> assertThat(app).doesNotHaveBean(GlobalCatalogController.class));
        context.withPropertyValues("ls.catalog.api.enabled=true")
                .run(app -> assertThat(app).doesNotHaveBean(GlobalCatalogController.class));
        context.withInitializer(app -> app.getEnvironment().setActiveProfiles("catalog-docker"))
                .run(app -> assertThat(app).doesNotHaveBean(GlobalCatalogController.class));
    }

    @Test
    void rejectsExternalBindingsAndWrongDatabaseTargets() {
        for (String key : List.of("server.address", "LS_CATALOG_DB_HOST", "LS_CATALOG_DB_PORT",
                "LS_CATALOG_DB_NAME", "ls.catalog.enabled")) {
            assertThatThrownBy(() -> new GlobalCatalogController(mock(GlobalCatalogReader.class),
                    local().withProperty(key, key.equals("ls.catalog.enabled") ? "false" : "invalid")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void readsOnlyApplicationsAndModulesWithValidatedPages() throws Exception {
        var reader = mock(GlobalCatalogReader.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GlobalCatalogController(reader, local())).build();
        UUID id = UUID.fromString("3bc09866-f9f0-573c-82a0-fd72ec6be41f");
        when(reader.applications(100, 0)).thenReturn(List.of(
                new GlobalCatalogReader.Application(id, "ls-core", "LS Core", "Catalog", "PUBLISHED")));
        mvc.perform(get("/api/global-catalog/applications"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("LS Core")));
        mvc.perform(get("/api/global-catalog/applications/" + id + "/modules?limit=1&offset=10000"))
                .andExpect(status().isOk());
        verify(reader).modules(id, 1, 10000);
        mvc.perform(post("/api/global-catalog/applications")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/global-catalog/users")).andExpect(status().isNotFound());
    }

    @Test
    void invalidInputsNeverAcquireAConnection() throws Exception {
        var reader = mock(GlobalCatalogReader.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GlobalCatalogController(reader, local())).build();
        for (String query : List.of("limit=0", "limit=101", "offset=-1", "offset=10001", "limit=bad", "offset=99999999999")) {
            mvc.perform(get("/api/global-catalog/applications?" + query)).andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"error\":\"Invalid catalog request\"}"));
        }
        mvc.perform(get("/api/global-catalog/applications/invalid/modules")).andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test
    void unavailableHasNoJdbcDetailsAndCorsIsNotOpen() throws Exception {
        var reader = mock(GlobalCatalogReader.class);
        when(reader.applications(100, 0)).thenThrow(GlobalCatalogReader.CatalogUnavailableException.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GlobalCatalogController(reader, local())).build();
        var response = mvc.perform(get("/api/global-catalog/applications").header("Origin", "https://external.example"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"error\":\"Global catalog unavailable\"}")).andReturn().getResponse();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
