package dev.matchline.sequencer;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Successful admission to a bounded volatile queue. The future completes only
 * after the single writer applies (or business-rejects) the command.
 * With no journal (or OS_BUFFERED journaling), completion is not durable.
 * With a SYNC_EACH_RECORD journal, completion follows the forced decision.
 * The admission ticket itself NEVER acknowledges persistence. Exposes only
 * a CompletionStage (not the writer-owned mutable future).
 */
public record CommandTicket(long sequence, CompletionStage<SequencedResult> completion) {
    public CommandTicket {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(completion, "completion");
    }
}
