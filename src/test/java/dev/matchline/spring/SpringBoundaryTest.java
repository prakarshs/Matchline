package dev.matchline.spring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

/** Verify real network endpoints in the temporary Spring transport. */
final class SpringBoundaryTest {
    @Test
    void startsIndependentlyAndExposesHealthAndPrometheus() throws Exception {
        SpringApplication launcher = new SpringApplication(MatchlineSpringApplication.class);
        try (var context = launcher.run("--server.address=127.0.0.1", "--server.port=0")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://127.0.0.1:" + port;
            HttpResponse<String> health = get(client, base + "/api/v1/health");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"UP\"");
            HttpResponse<String> actuator = get(client, base + "/actuator/health");
            assertThat(actuator.statusCode()).isEqualTo(200);
            HttpResponse<String> metrics = get(client, base + "/actuator/prometheus");
            assertThat(metrics.statusCode()).isEqualTo(200);
            assertThat(metrics.body()).contains("jvm_memory_used_bytes");
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
