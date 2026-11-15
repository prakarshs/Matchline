package dev.matchline.sequencer;

import dev.matchline.domain.OrderCommand;

import java.util.Objects;

/**
 * Sequence is the successful *in-memory admission order*, NOT a persisted offset.
 * Even a state-dependent rejection has a sequence if its command entered the queue.
 */
/** Ordered decision. Persistence strength is configured on the sequencer, not this value type. */
public record SequencedResult(long sequence, OrderCommand command, CommandDecision decision) {
    public SequencedResult {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(decision, "decision");
        if (command.kind() == OrderCommand.Kind.PLACE_LIMIT
                && decision instanceof CommandDecision.Cancelled) {
            throw new IllegalArgumentException("place command cannot return a cancellation");
        }
        if (command.kind() == OrderCommand.Kind.CANCEL
                && decision instanceof CommandDecision.Placed) {
            throw new IllegalArgumentException("cancel command cannot return a placement");
        }
    }

    public boolean successful() {
        return !(decision instanceof CommandDecision.Rejected);
    }
}
