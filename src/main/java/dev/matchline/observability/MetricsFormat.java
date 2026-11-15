package dev.matchline.observability;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON-friendly DTO values and Prometheus text; fixed metric names, no dynamic labels. */
public final class MetricsFormat {
    private MetricsFormat() { }

    public static Map<String, Object> asJson(EngineMetrics.Snapshot engine, HttpMetrics.Snapshot http,
                                              long generation, long sequence, int queued, int capacity) {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("instrument", "DEMO");
        outer.put("generation", generation);
        outer.put("lastProcessedSequence", sequence);
        outer.put("queuedWork", queued);
        outer.put("queueCapacity", capacity);
        Map<String, Object> writer = new LinkedHashMap<>();
        writer.put("admitted", engine.commandsAdmitted());
        writer.put("completed", engine.commandsCompleted());
        writer.put("businessRejected", engine.businessRejected());
        writer.put("ingressRejectedFull", engine.rejectedFull());
        writer.put("ingressRejectedClosed", engine.rejectedClosed());
        writer.put("readsAdmitted", engine.readsAdmitted());
        writer.put("fatalFailures", engine.writerFailures());
        outer.put("writer", writer);
        outer.put("queueWaitNanos", histogramJson(engine.queueWaitNanos()));
        outer.put("processingNanos", histogramJson(engine.processingNanos()));
        outer.put("acknowledgementNanos", histogramJson(engine.acknowledgementNanos()));
        outer.put("http", Map.of("responses2xx", http.responses2xx(), "responses4xx", http.responses4xx(),
                "responses5xx", http.responses5xx(), "requestNanos", histogramJson(http.requestNanos())));
        outer.put("note", "Approximate histograms; percentile values are bucket upper bounds in nanoseconds. "
                + "Counters reset when the owning process/engine starts; a demo reset replaces the engine only.");
        return outer;
    }

    private static Map<String, Object> histogramJson(BoundedHistogram.Snapshot histogram) {
        return Map.of("count", histogram.count(), "sumNanos", histogram.sumNanos(),
                "p50UpperBoundNanos", histogram.percentileUpperBound(50),
                "p95UpperBoundNanos", histogram.percentileUpperBound(95),
                "p99UpperBoundNanos", histogram.percentileUpperBound(99));
    }

    public static String prometheus(EngineMetrics.Snapshot engine, HttpMetrics.Snapshot http,
                                    long generation, int queued, int capacity) {
        StringBuilder out = new StringBuilder(2_800);
        gauge(out, "matchline_generation", "Current volatile demo generation", generation);
        gauge(out, "matchline_ingress_queue_work", "Approximate queued work items", queued);
        gauge(out, "matchline_ingress_queue_capacity", "Bounded inbox slots", capacity);
        counter(out, "matchline_commands_admitted_total", "Commands admitted to the writer", engine.commandsAdmitted());
        counter(out, "matchline_commands_completed_total", "Completed command decisions", engine.commandsCompleted());
        counter(out, "matchline_commands_business_rejected_total", "Sequenced business rejections", engine.businessRejected());
        counter(out, "matchline_ingress_full_total", "Full inbox admission rejections", engine.rejectedFull());
        counter(out, "matchline_ingress_closed_total", "Closed inbox admission rejections", engine.rejectedClosed());
        counter(out, "matchline_reads_admitted_total", "Writer-ordered diagnostic reads", engine.readsAdmitted());
        counter(out, "matchline_writer_failures_total", "Unexpected writer failures", engine.writerFailures());
        counter(out, "matchline_http_responses_2xx_total", "HTTP 2xx responses", http.responses2xx());
        counter(out, "matchline_http_responses_4xx_total", "HTTP 4xx responses", http.responses4xx());
        counter(out, "matchline_http_responses_5xx_total", "HTTP 5xx responses", http.responses5xx());
        histogram(out, "matchline_writer_queue_wait_seconds", "Time between command admission and execution start", engine.queueWaitNanos());
        histogram(out, "matchline_writer_processing_seconds", "Time spent processing a command, including journaling and snapshots", engine.processingNanos());
        histogram(out, "matchline_writer_acknowledgement_seconds", "Admission to completed decision", engine.acknowledgementNanos());
        histogram(out, "matchline_http_request_seconds", "Whole HTTP request handling duration", http.requestNanos());
        return out.toString();
    }

    private static void counter(StringBuilder out, String key, String help, long value) {
        type(out, key, help, "counter");
        out.append(key).append(' ').append(value).append('\n');
    }

    private static void gauge(StringBuilder out, String key, String help, long value) {
        type(out, key, help, "gauge");
        out.append(key).append(' ').append(value).append('\n');
    }

    private static void type(StringBuilder out, String key, String help, String type) {
        out.append("# HELP ").append(key).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(key).append(' ').append(type).append('\n');
    }

    private static void histogram(StringBuilder out, String key, String help, BoundedHistogram.Snapshot snapshot) {
        type(out, key, help, "histogram");
        long[] counts = snapshot.buckets();
        long cumulative = 0;
        long[] bounds = BoundedHistogram.bucketUpperBoundsNanos();
        for (int i = 0; i < bounds.length; i++) {
            cumulative += counts[i];
            out.append(key).append("_bucket{le=\"")
                    .append(bounds[i] / 1_000_000_000.0)
                    .append("\"} ").append(cumulative).append('\n');
        }
        out.append(key).append("_bucket{le=\"+Inf\"} ").append(snapshot.count()).append('\n');
        out.append(key).append("_sum ").append(snapshot.sumNanos() / 1_000_000_000.0).append('\n');
        out.append(key).append("_count ").append(snapshot.count()).append('\n');
    }
}
