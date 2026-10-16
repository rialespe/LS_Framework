package com.lssoluciones.lscore;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

abstract class AbstractBackendSmokeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStartsWithoutPostgresInfrastructure() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.containsBean("postgresHealthIndicator")).isFalse();
    }

    @Test
    void healthIsUpWithoutExposingDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("UP");
        assertThat(JsonPath.<java.util.Map<String, Object>>read(response.body(), "$"))
                .doesNotContainKeys("components", "details");
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "beans", "configprops"})
    void administrativeActuatorEndpointsAreNotExposed(String endpoint) throws Exception {
        assertThat(get("/actuator/" + endpoint).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
