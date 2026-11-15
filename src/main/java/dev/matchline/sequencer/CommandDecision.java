package dev.matchline.sequencer;

import dev.matchline.book.CancelResult;
import dev.matchline.book.MatchResult;
import dev.matchline.domain.CommandRejection;

import java.util.Objects;

/** One *ordered*, in-memory business outcome. No persistence or durability is implied. */
public sealed interface CommandDecision
        permits CommandDecision.Placed, CommandDecision.Cancelled, CommandDecision.Rejected {

    record Placed(MatchResult result) implements CommandDecision {
        public Placed {
            Objects.requireNonNull(result, "result");
        }
    }

    record Cancelled(CancelResult result) implements CommandDecision {
        public Cancelled {
            Objects.requireNonNull(result, "result");
        }
    }

    record Rejected(CommandRejection rejection) implements CommandDecision {
        public Rejected {
            Objects.requireNonNull(rejection, "rejection");
        }
    }
}
