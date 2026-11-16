package dev.matchline.spring.api;

import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.OrderValidationException;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import java.util.Map;
import java.util.Set;

/**
 * Request DTO at the HTTP boundary. Parse a JSON object without silently coercing
 * strings/fractions/booleans into numeric domain values. Only this class knows the
 * wire field names; the matcher continues to accept typed PlaceLimitOrder.
 *
 * <p>Using a Map for initial Jackson binding is intentional: it allows checking
 * exact field presence and raw value types before constructing this DTO. This is
 * NOT a second set of domain validation rules.
 */
public record PlaceOrderRequest(String instrument, String orderId, String side,
                                long priceTicks, long quantity) {
    private static final Set<String> REQUIRED =
            Set.of("instrument", "orderId", "side", "priceTicks", "quantity");

    public static PlaceOrderRequest fromJson(Map<String, Object> body) {
        if (body == null || !REQUIRED.equals(body.keySet())) {
            throw new SpringApiException(400, "INVALID_FIELDS",
                    "required fields: instrument, orderId, side, priceTicks, quantity; no extras");
        }
        return new PlaceOrderRequest(text(body, "instrument"), text(body, "orderId"),
                text(body, "side"), integral(body, "priceTicks"), integral(body, "quantity"));
    }

    public PlaceLimitOrder toCommand() {
        Side parsed;
        try {
            parsed = Side.valueOf(side);
        } catch (IllegalArgumentException invalid) {
            throw new OrderValidationException(RejectionCode.INVALID_SIDE, "side must be BUY or SELL");
        }
        return new PlaceLimitOrder(new InstrumentId(instrument), new OrderId(orderId),
                parsed, new PriceTicks(priceTicks), new OrderQuantity(quantity));
    }

    private static String text(Map<String, Object> body, String field) {
        if (body.get(field) instanceof String text) return text;
        throw new SpringApiException(400, "INVALID_FIELD_TYPE", field + " must be a JSON string");
    }

    private static long integral(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value instanceof Byte n) return n.longValue();
        if (value instanceof Short n) return n.longValue();
        if (value instanceof Integer n) return n.longValue();
        if (value instanceof Long n) return n;
        throw new SpringApiException(400, "INVALID_FIELD_TYPE", field + " must be an integer");
    }
}
