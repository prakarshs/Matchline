package dev.matchline.recovery;

/** Returned at restart; a resolved pending command was NEVER an acknowledged outcome at crash time. */
public record RecoveryReport(long snapshotSequence, long replayedDecisions,
                             long recoveredSequence, boolean resolvedPendingCommand) {
    public RecoveryReport {
        if (snapshotSequence < 0 || replayedDecisions < 0 || recoveredSequence < snapshotSequence
                || replayedDecisions != recoveredSequence - snapshotSequence
                        - (resolvedPendingCommand ? 1 : 0)) {
            throw new IllegalArgumentException("inconsistent recovery counters");
        }
    }
}
