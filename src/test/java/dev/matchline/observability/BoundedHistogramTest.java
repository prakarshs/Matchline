package dev.matchline.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoundedHistogramTest {
    @Test void emptyHistogramHasNoPercentileEstimate() {
        var histogram = new BoundedHistogram();
        var snapshot = histogram.snapshot();
        assertEquals(0, snapshot.count());
        assertEquals(-1, snapshot.percentileUpperBound(50));
        assertEquals(-1, snapshot.percentileUpperBound(99));
    }

    @Test void percentileIsUpperBucketBoundaryNotRawObservation() {
        var histogram = new BoundedHistogram();
        histogram.observeNanos(1);
        histogram.observeNanos(49_999);
        histogram.observeNanos(50_000);
        histogram.observeNanos(100_001);
        var snap = histogram.snapshot();
        assertEquals(4, snap.count());
        assertEquals(200_001, snap.sumNanos());
        assertEquals(50_000, snap.percentileUpperBound(50));
        assertEquals(250_000, snap.percentileUpperBound(95));
        assertEquals(3, snap.buckets()[0]);
        assertEquals(1, snap.buckets()[2]);
        assertEquals(0, snap.buckets()[1]);
    }

    @Test void overflowBucketAndImmutableCopyAreSupported() {
        var histogram = new BoundedHistogram();
        histogram.observeNanos(5_000_000_001L);
        var snap = histogram.snapshot();
        assertEquals(Long.MAX_VALUE, snap.percentileUpperBound(99));
        long[] copy = snap.buckets();
        copy[copy.length - 1] = 0;
        assertEquals(1, snap.buckets()[copy.length - 1]);
        assertThrows(IllegalArgumentException.class, () -> histogram.observeNanos(-1));
        assertThrows(IllegalArgumentException.class, () -> snap.percentileUpperBound(0));
        assertThrows(IllegalArgumentException.class, () -> snap.percentileUpperBound(101));
    }

    @Test void prometheusBucketsAreCumulativeAndEndAtCount() {
        var metrics = new EngineMetrics();
        metrics.admittedCommand();
        metrics.completedCommand(true, 10, 100, 110);
        var text = MetricsFormat.prometheus(metrics.snapshot(), new HttpMetrics().snapshot(), 1, 0, 8);
        assertTrue(text.contains("matchline_commands_admitted_total 1"));
        assertTrue(text.contains("matchline_commands_business_rejected_total 1"));
        assertTrue(text.contains("matchline_writer_processing_seconds_bucket{le=\"+Inf\"} 1"));
        assertTrue(text.contains("matchline_writer_processing_seconds_count 1"));
        assertTrue(text.contains("matchline_writer_processing_seconds_sum 1.0E-7"));
        assertFalse(text.contains("orderId="));
    }
}
