package dev.matchline.sequencer;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Successful admission to a bounded volatile queue. The future completes only
 * after the single writer applies (or business-rejects) the command.
 * Neither admission nor completion means the command was made durable.
 * Exposes only a CompletionStage (not the writer-owned mutable future).
 */
public record CommandTicket(long sequence, CompletionStage<SequencedResult> completion) {
    public CommandTicket {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(completion, "completion");
    }
}
