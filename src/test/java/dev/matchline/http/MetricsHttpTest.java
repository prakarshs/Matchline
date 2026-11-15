package dev.matchline.http;

import dev.matchline.journal.DurabilityPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MetricsHttpTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void jsonAndPrometheusMetricsDescribeRealWriterDecisions() throws Exception {
        HttpConfig config = new HttpConfig(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                null, null, null, null, DurabilityPolicy.SYNC_EACH_RECORD, 64);
        try (var server = new MatchlineHttpServer(config)) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.address().getPort());
            var placed = HttpRequest.newBuilder(base.resolve("/api/v1/orders"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"instrument\":\"DEMO\",\"orderId\":\"B10\",\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":4}"))
                    .build();
            assertEquals(200, http.send(placed, HttpResponse.BodyHandlers.ofString()).statusCode());
            var duplicate = http.send(placed, HttpResponse.BodyHandlers.ofString());
            assertEquals(409, duplicate.statusCode());

            var metrics = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("\"admitted\":2"));
            assertTrue(metrics.body().contains("\"completed\":2"));
            assertTrue(metrics.body().contains("\"businessRejected\":1"));
            assertTrue(metrics.body().contains("\"p99UpperBoundNanos\""));
            assertTrue(metrics.body().contains("\"responses4xx\":1"));
            assertFalse(metrics.body().contains("B10"));

            var prometheus = http.send(HttpRequest.newBuilder(base.resolve("/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, prometheus.statusCode());
            assertTrue(prometheus.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
            assertTrue(prometheus.body().contains("matchline_commands_completed_total 2"));
            assertTrue(prometheus.body().contains("matchline_commands_business_rejected_total 1"));
            assertTrue(prometheus.body().contains("matchline_writer_acknowledgement_seconds_count 2"));
            assertFalse(prometheus.body().contains("B10"));
        }
    }

    @Test void unknownMethodAndPathStillReturnStandardErrors() throws Exception {
        var config = new HttpConfig(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                null, null, null, null, DurabilityPolicy.SYNC_EACH_RECORD, 64);
        try (var server = new MatchlineHttpServer(config)) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.address().getPort());
            assertEquals(404, http.send(HttpRequest.newBuilder(base.resolve("/bad-metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(base.resolve("/metrics"))
                            .method("PUT", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var metrics = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(metrics.body().contains("\"responses4xx\":2"));
        }
    }
}
