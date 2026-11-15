package dev.matchline.domain;

/** Cancel the remaining resting quantity of an order; concurrent sequencing comes later. */
public record CancelOrder(InstrumentId instrument, OrderId orderId) implements OrderCommand {
    public CancelOrder {
        if (instrument == null || orderId == null) {
            throw new OrderValidationException(RejectionCode.INVALID_COMMAND,
                    "cancel command requires instrument and order ID");
        }
    }

    @Override
    public Kind kind() {
        return Kind.CANCEL;
    }
}
