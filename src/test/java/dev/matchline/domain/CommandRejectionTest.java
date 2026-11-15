package dev.matchline.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandRejectionTest {
    @Test
    void validationFailureCanBecomeAStableRejectionValue() {
        OrderValidationException validation = assertThrows(OrderValidationException.class,
                () -> new OrderQuantity(0));
        CommandRejection rejection = CommandRejection.fromValidation(validation);
        assertThat(rejection.code()).isEqualTo(RejectionCode.INVALID_QUANTITY);
        assertThat(rejection.message()).contains("quantity");
    }

    @Test
    void stateDependentReasonsAreDeclaredButNotYetExecuted() {
        assertThat(new CommandRejection(RejectionCode.DUPLICATE_ORDER_ID, "ID already used").code())
                .isEqualTo(RejectionCode.DUPLICATE_ORDER_ID);
        assertThat(new CommandRejection(RejectionCode.ORDER_NOT_FOUND, "No order").code())
                .isEqualTo(RejectionCode.ORDER_NOT_FOUND);
    }

    @Test
    void invalidRejectionMetadataFailsFast() {
        assertThrows(NullPointerException.class, () -> new CommandRejection(null, "message"));
        assertThrows(IllegalArgumentException.class, () -> new CommandRejection(RejectionCode.INVALID_COMMAND, null));
        assertThrows(IllegalArgumentException.class, () -> new CommandRejection(RejectionCode.INVALID_COMMAND, ""));
        assertThrows(IllegalArgumentException.class, () -> new CommandRejection(RejectionCode.INVALID_COMMAND, "   "));
        assertThrows(IllegalArgumentException.class, () -> new CommandRejection(RejectionCode.INVALID_COMMAND, "A".repeat(513)));
        assertThrows(NullPointerException.class, () -> CommandRejection.fromValidation(null));
    }

    @Test
    void validationExceptionHasBoundedTypedCause() {
        assertThrows(NullPointerException.class, () -> new OrderValidationException(null, "bad"));
        assertThrows(NullPointerException.class, () -> new OrderValidationException(RejectionCode.INVALID_ORDER_ID, null));
        assertThrows(IllegalArgumentException.class, () -> new OrderValidationException(RejectionCode.INVALID_ORDER_ID, " "));
    }
}
