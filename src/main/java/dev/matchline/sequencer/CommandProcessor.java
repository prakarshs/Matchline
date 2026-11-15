package dev.matchline.sequencer;

import dev.matchline.book.LimitOrderBook;
import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.CommandRejection;
import dev.matchline.domain.OrderCommand;
import dev.matchline.domain.OrderValidationException;
import dev.matchline.domain.PlaceLimitOrder;

/** Single canonical business-decision function shared by live execution and deterministic replay. */
final class CommandProcessor {
    private CommandProcessor() { }

    static CommandDecision apply(LimitOrderBook book, OrderCommand command) {
        try {
            if (command instanceof PlaceLimitOrder place) {
                return new CommandDecision.Placed(book.executeLimit(place));
            }
            if (command instanceof CancelOrder cancel) {
                return new CommandDecision.Cancelled(book.cancel(cancel));
            }
            throw new IllegalStateException("unrecognized OrderCommand subtype");
        } catch (OrderValidationException rejected) {
            return new CommandDecision.Rejected(CommandRejection.fromValidation(rejected));
        }
    }
}
