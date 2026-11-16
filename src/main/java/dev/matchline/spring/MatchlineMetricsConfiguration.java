package dev.matchline.spring;

import dev.matchline.http.DemoSessions;
import dev.matchline.spring.demo.SpringDemoProperties;
import dev.matchline.spring.demo.SpringRequestGuard;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fixed-name process metrics. Never attach session tokens, order IDs, or
 * instrument values as Prometheus labels (cardinality and privacy risk).
 *
 * Spring's singleton writer is used only in LOCAL mode. Public visitors each
 * own a different short-lived engine, so the local writer meters are explicitly
 * named "local"; the public per-session counters remain in /api/v1/metrics.
 */
@Configuration(proxyBeanMethods = false)
public class MatchlineMetricsConfiguration {
    @Bean
    MeterBinder matchlineMeters(MatchingApplicationService local, SpringDemoProperties demo,
                                SpringRequestGuard guard, DemoSessions sessions) {
        return registry -> {
            Gauge.builder("matchline.demo.enabled", demo, p -> p.isEnabled() ? 1 : 0)
                    .description("One if the server runs in isolated public-demo mode").register(registry);
            Gauge.builder("matchline.demo.active.sessions", sessions, DemoSessions::count)
                    .description("Currently retained isolated visitor sessions").register(registry);
            Gauge.builder("matchline.demo.session.limit", () -> DemoSessions.MAX_SESSIONS)
                    .description("Maximum simultaneously retained demo sessions").register(registry);
            Gauge.builder("matchline.http.active.requests", guard, SpringRequestGuard::activeRequests)
                    .description("HTTP requests that currently hold an admission slot").register(registry);
            Gauge.builder("matchline.http.concurrent.limit", guard, SpringRequestGuard::maxConcurrentRequests)
                    .description("Maximum simultaneously admitted HTTP requests").register(registry);
            Gauge.builder("matchline.local.writer.queue.depth", local, MatchingApplicationService::queuedWorkCount)
                    .description("Work waiting in the local singleton writer inbox").register(registry);
            Gauge.builder("matchline.local.writer.queue.capacity", local, MatchingApplicationService::queueCapacity)
                    .description("Capacity of the local singleton writer inbox").register(registry);
            Gauge.builder("matchline.local.writer.last.sequence", local, MatchingApplicationService::lastProcessedSequence)
                    .description("Last processed sequence in the LOCAL writer (not visitor sessions)")
                    .register(registry);
            FunctionCounter.builder("matchline.local.writer.commands.completed", local,
                            service -> service.metricsSnapshot().commandsCompleted())
                    .description("Completed commands in LOCAL writer since process startup")
                    .register(registry);
        };
    }
}
