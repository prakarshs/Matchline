package dev.matchline.observability;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-memory, lock-free, approximate distribution of positive monotonic durations.
 * Buckets are *upper bounds*, not precise percentiles. Snapshot reads are weakly
 * consistent during concurrent recording. No per-request identity is retained.
 */
public final class BoundedHistogram {
    private static final long[] BOUNDS_NANOS = {
            50_000L, 100_000L, 250_000L, 500_000L, 1_000_000L,
            2_500_000L, 5_000_000L, 10_000_000L, 25_000_000L,
            50_000_000L, 100_000_000L, 250_000_000L, 500_000_000L,
            1_000_000_000L, 5_000_000_000L
    };

    /** Detached boundaries for text-format exporters. */
    public static long[] bucketUpperBoundsNanos() { return BOUNDS_NANOS.clone(); }

    private final AtomicLongArray bins = new AtomicLongArray(BOUNDS_NANOS.length + 1);
    private final LongAdder sumNanos = new LongAdder();

    public void observeNanos(long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("duration must not be negative");
        int bucket = 0;
        while (bucket < BOUNDS_NANOS.length && nanos > BOUNDS_NANOS[bucket]) bucket++;
        bins.incrementAndGet(bucket);
        sumNanos.add(nanos);
    }

    public Snapshot snapshot() {
        long[] counts = new long[bins.length()];
        long total = 0;
        for (int i = 0; i < counts.length; i++) {
            counts[i] = bins.get(i);
            total += counts[i];
        }
        return new Snapshot(counts, total, sumNanos.sum());
    }

    public record Snapshot(long[] buckets, long count, long sumNanos) {
        public Snapshot {
            Objects.requireNonNull(buckets, "buckets");
            if (buckets.length != BOUNDS_NANOS.length + 1 || count < 0 || sumNanos < 0) {
                throw new IllegalArgumentException("invalid histogram snapshot");
            }
            buckets = buckets.clone();
        }

        @Override public long[] buckets() { return buckets.clone(); }

        /** Upper bound in nanoseconds; -1 means no samples; Long.MAX_VALUE means +Inf. */
        public long percentileUpperBound(int percentile) {
            if (percentile < 1 || percentile > 100) throw new IllegalArgumentException("percentile 1..100");
            if (count == 0) return -1;
            long rank = Math.max(1L, (long) Math.ceil(count * (percentile / 100.0)));
            long cumulative = 0;
            for (int i = 0; i < buckets.length; i++) {
                cumulative += buckets[i];
                if (cumulative >= rank) {
                    return i == BOUNDS_NANOS.length ? Long.MAX_VALUE : BOUNDS_NANOS[i];
                }
            }
            throw new IllegalStateException("histogram counts do not match total");
        }

        @Override public String toString() {
            return "Snapshot[count=" + count + ", buckets=" + Arrays.toString(buckets) + "]";
        }
    }
}
