package dev.matchline.spring;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Cheap, non-mutating engine health signal for Actuator.
 *
 * <p>Never submit a probe order, block the writer, or pretend this checks
 * filesystem durability. A live writer reports UP, while shutdown or a
 * fatal writer exception reports DOWN. This is process-local readiness evidence.
 */
@Component("matchlineEngine")
public final class MatchingEngineHealthIndicator implements HealthIndicator {
    private final MatchingApplicationService engine;

    public MatchingEngineHealthIndicator(MatchingApplicationService engine) {
        this.engine = engine;
    }

    @Override
    public Health health() {
        if (!engine.accepting() || engine.fatalFailure().isPresent()) {
            return Health.down().build();
        }
        return Health.up().build();
    }
}
