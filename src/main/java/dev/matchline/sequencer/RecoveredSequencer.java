package dev.matchline.sequencer;

import dev.matchline.recovery.RecoveryReport;

import java.util.Objects;

/** Owns an already-running recovered writer and its verified restart report. */
public record RecoveredSequencer(SingleWriterSequencer sequencer, RecoveryReport report)
        implements AutoCloseable {
    public RecoveredSequencer {
        Objects.requireNonNull(sequencer, "sequencer");
        Objects.requireNonNull(report, "report");
    }

    @Override
    public void close() {
        sequencer.close();
    }
}
