package dev.matchline.journal;

import dev.matchline.domain.OrderCommand;
import dev.matchline.sequencer.CommandDecision;

import java.util.Objects;

/** Version-1 logical frames, in physical append order; there is no clock dependency. */
public sealed interface JournalEntry permits JournalEntry.Command, JournalEntry.Decision {
    long sequence();

    record Command(long sequence, OrderCommand command) implements JournalEntry {
        public Command {
            if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
            Objects.requireNonNull(command, "command");
        }
    }

    record Decision(long sequence, CommandDecision decision) implements JournalEntry {
        public Decision {
            if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
            Objects.requireNonNull(decision, "decision");
        }
    }
}
