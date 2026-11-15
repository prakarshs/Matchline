package dev.matchline.domain;

/** Cancel the identified order in its declared instrument, when sequenced later. */
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
