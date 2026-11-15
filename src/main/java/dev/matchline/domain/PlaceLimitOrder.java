package dev.matchline.domain;

/** Immutable validated limit-order command. No state change happens on construction. */
public record PlaceLimitOrder(
        InstrumentId instrument,
        OrderId orderId,
        Side side,
        PriceTicks price,
        OrderQuantity quantity
) implements OrderCommand {

    public PlaceLimitOrder {
        if (instrument == null || orderId == null || price == null || quantity == null) {
            throw new OrderValidationException(RejectionCode.INVALID_COMMAND,
                    "place-limit command requires instrument, order ID, price and quantity");
        }
        if (side == null) {
            throw new OrderValidationException(RejectionCode.INVALID_SIDE,
                    "place-limit command requires BUY or SELL");
        }
    }

    @Override
    public Kind kind() {
        return Kind.PLACE_LIMIT;
    }
}
