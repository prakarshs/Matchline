package dev.matchline.journal;

import dev.matchline.domain.InstrumentId;

import java.util.List;
import java.util.Objects;

/** A detached, validated journal view. An unmatched final COMMAND is unacknowledged. */
public record JournalScan(InstrumentId instrument, List<JournalEntry> entries,
                          long completedDecisionCount, long pendingCommandSequence,
                          long validBytes, long truncatedBytes) {
    public JournalScan {
        Objects.requireNonNull(instrument, "instrument");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (completedDecisionCount < 0 || pendingCommandSequence < 0 || validBytes < 0 || truncatedBytes < 0) {
            throw new IllegalArgumentException("negative scan values");
        }
    }

    public boolean hasUnacknowledgedCommand() {
        return pendingCommandSequence != 0;
    }
}
