package dev.matchline.spring;

import dev.matchline.domain.InstrumentId;
import dev.matchline.sequencer.SingleWriterSequencer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validate typed property binding, startup failure paths, and health truthfulness. */
final class SpringEngineConfigurationTest {
    @Test
    void overriddenBoundsAndInstrumentAreUsedForTheOnlyWriter() {
        try (var context = open("--matchline.engine.instrument=ABC-1",
                "--matchline.engine.queue-capacity=17",
                "--matchline.engine.max-resting-orders=19",
                "--matchline.engine.max-order-ids=21")) {
            var service = context.getBean(MatchingApplicationService.class);
            var props = context.getBean(MatchlineEngineProperties.class);
            assertThat(service.instrument()).isEqualTo(new InstrumentId("ABC-1"));
            assertThat(service.queueCapacity()).isEqualTo(17);
            assertThat(props.getMaxRestingOrders()).isEqualTo(19);
            assertThat(props.getMaxOrderIds()).isEqualTo(21);
            assertThat(context.getBeansOfType(SingleWriterSequencer.class)).hasSize(1);
        }
    }

    @Test
    void customInstrumentIsVisibleInActualMvcHealthResponse() throws Exception {
        SpringApplication app = new SpringApplication(MatchlineSpringApplication.class);
        try (var context = app.run("--server.port=0", "--server.address=127.0.0.1",
                "--matchline.engine.instrument=TEST")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var response = client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"instrument\":\"TEST\"");
            var actuator = client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(actuator.statusCode()).isEqualTo(200);
            assertThat(context.getBean(MatchingEngineHealthIndicator.class).health().getStatus().getCode())
                    .isEqualTo("UP");
        }
    }

    @Test
    void invalidInstrumentFailsDuringStartup() {
        invalid("--matchline.engine.instrument=lowercase", "instrument must");
    }

    @Test
    void zeroQueueCapacityFailsDuringStartup() {
        invalid("--matchline.engine.queue-capacity=0", "queue-capacity");
    }

    @Test
    void excessiveQueueCapacityFailsDuringStartup() {
        invalid("--matchline.engine.queue-capacity=65537", "queue-capacity");
    }

    @Test
    void zeroRestingOrderCapacityFailsDuringStartup() {
        invalid("--matchline.engine.max-resting-orders=0", "max-resting-orders");
    }

    @Test
    void excessiveOrderIdHistoryFailsDuringStartup() {
        invalid("--matchline.engine.max-order-ids=2000001", "max-order-ids");
    }

    private static void invalid(String setting, String rootMessage) {
        assertThatThrownBy(() -> open(setting)).satisfies(failure -> {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            assertThat(cause.getMessage()).contains(rootMessage);
        });
    }

    private static ConfigurableApplicationContext open(String... overrides) {
        SpringApplication app = new SpringApplication(MatchlineSpringApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run(overrides);
    }
}
