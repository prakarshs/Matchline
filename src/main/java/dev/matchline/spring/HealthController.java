package dev.matchline.spring;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compatibility-shaped health response for the new Spring transport.
 * This is process liveness, not proof that an exchange order has been processed.
 */
@RestController
public final class HealthController {
    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "instrument", "DEMO");
    }
}
