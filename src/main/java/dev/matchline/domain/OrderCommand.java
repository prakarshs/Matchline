package dev.matchline.domain;

/**
 * A validated request to mutate one instrument's order book in a future commit.
 * This contract does not allocate sequence numbers or acknowledge durability.
 */
public sealed interface OrderCommand permits PlaceLimitOrder, CancelOrder {
    enum Kind { PLACE_LIMIT, CANCEL }

    InstrumentId instrument();

    OrderId orderId();

    Kind kind();
}
