package dev.matchline.observability;

import java.util.concurrent.atomic.LongAdder;

/** Process-lifetime, route-agnostic HTTP metrics with no token/order-ID labels. */
public final class HttpMetrics {
    private final LongAdder responses2xx = new LongAdder();
    private final LongAdder responses4xx = new LongAdder();
    private final LongAdder responses5xx = new LongAdder();
    private final BoundedHistogram request = new BoundedHistogram();

    public void response(int status) {
        if (status >= 200 && status < 300) responses2xx.increment();
        else if (status >= 400 && status < 500) responses4xx.increment();
        else if (status >= 500 && status < 600) responses5xx.increment();
    }

    public void duration(long nanos) { request.observeNanos(Math.max(0L, nanos)); }

    public Snapshot snapshot() {
        return new Snapshot(responses2xx.sum(), responses4xx.sum(), responses5xx.sum(), request.snapshot());
    }

    public record Snapshot(long responses2xx, long responses4xx, long responses5xx,
                           BoundedHistogram.Snapshot requestNanos) { }
}
