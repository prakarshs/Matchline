package dev.matchline.observability;

import java.util.concurrent.atomic.LongAdder;

/** Per-sequencer counters. Observational only: never changes command ordering or decisions. */
public final class EngineMetrics {
    private final LongAdder admitted = new LongAdder();
    private final LongAdder rejectedFull = new LongAdder();
    private final LongAdder rejectedClosed = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder businessRejected = new LongAdder();
    private final LongAdder reads = new LongAdder();
    private final LongAdder writerFailures = new LongAdder();
    private final BoundedHistogram queueWait = new BoundedHistogram();
    private final BoundedHistogram processing = new BoundedHistogram();
    private final BoundedHistogram acknowledgement = new BoundedHistogram();

    public void admittedCommand() { admitted.increment(); }
    public void rejectedFull() { rejectedFull.increment(); }
    public void rejectedClosed() { rejectedClosed.increment(); }
    public void completedCommand(boolean rejected, long queueNanos, long processingNanos, long ackNanos) {
        queueWait.observeNanos(queueNanos);
        processing.observeNanos(processingNanos);
        acknowledgement.observeNanos(ackNanos);
        if (rejected) businessRejected.increment();
        completed.increment();
    }
    public void admittedRead() { reads.increment(); }
    public void failedWriter() { writerFailures.increment(); }

    /** Reads can be weakly consistent during active submissions. */
    public Snapshot snapshot() {
        return new Snapshot(admitted.sum(), rejectedFull.sum(), rejectedClosed.sum(),
                completed.sum(), businessRejected.sum(), reads.sum(), writerFailures.sum(),
                queueWait.snapshot(), processing.snapshot(), acknowledgement.snapshot());
    }

    public record Snapshot(long commandsAdmitted, long rejectedFull, long rejectedClosed,
                           long commandsCompleted, long businessRejected, long readsAdmitted,
                           long writerFailures, BoundedHistogram.Snapshot queueWaitNanos,
                           BoundedHistogram.Snapshot processingNanos,
                           BoundedHistogram.Snapshot acknowledgementNanos) { }
}
