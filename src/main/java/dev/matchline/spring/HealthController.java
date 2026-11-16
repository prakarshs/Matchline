package dev.matchline.spring;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compatibility-shaped health response for the temporary Spring HTTP transport.
 * S02 ties instrument reporting to the actual Spring-owned writer configuration.
 * This is NOT proof that an order has been matched or journaled.
 */
@RestController
public final class HealthController {
    private final MatchingApplicationService engine;

    public HealthController(MatchingApplicationService engine) {
        this.engine = engine;
    }

    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of("status", engine.accepting() && engine.fatalFailure().isEmpty() ? "UP" : "DOWN",
                "instrument", engine.instrument().value());
    }
}
