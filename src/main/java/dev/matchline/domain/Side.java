package dev.matchline.domain;

import java.util.Objects;

/** Buy or sell side. Only LIMIT prices are supported in the initial engine. */
public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    /**
     * Whether this incoming side and its limit can trade at the resting price.
     * Pure arithmetic predicate; this method does NOT execute a trade.
     */
    public boolean crosses(PriceTicks incomingLimit, PriceTicks restingPrice) {
        Objects.requireNonNull(incomingLimit, "incomingLimit");
        Objects.requireNonNull(restingPrice, "restingPrice");
        return this == BUY
                ? incomingLimit.compareTo(restingPrice) >= 0
                : incomingLimit.compareTo(restingPrice) <= 0;
    }
}
