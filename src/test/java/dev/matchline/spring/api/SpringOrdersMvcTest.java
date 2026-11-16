package dev.matchline.spring.api;

import dev.matchline.spring.MatchlineSpringApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Spring context + MVC message conversion + the actual single writer.
 * Each test gets a fresh context so order IDs, sequences and FIFO are isolated.
 * Unlike mocks, these checks exercise our real domain classes and concurrency path.
 */
@SpringBootTest(classes = MatchlineSpringApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
final class SpringOrdersMvcTest {
    @Autowired MockMvc mvc;

    private static String order(String id, String side, long price, long qty) {
        return "{\"instrument\":\"DEMO\",\"orderId\":\"" + id + "\",\"side\":\"" + side
                + "\",\"priceTicks\":" + price + ",\"quantity\":" + qty + "}";
    }
    private void submit(String id, String side, long price, long qty) throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(order(id, side, price, qty)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("PLACED"));
    }

    @Test void placementResponseAndOrderedBookMatchOriginalWireContract() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(order("B1", "BUY", 100, 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.decision").value("PLACED"))
                .andExpect(jsonPath("$.durable").value(false))
                .andExpect(jsonPath("$.executedQuantity").value(0))
                .andExpect(jsonPath("$.restingQuantity").value(4))
                .andExpect(jsonPath("$.trades").isEmpty());
        mvc.perform(get("/api/v1/book?depth=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfSequence").value(1))
                .andExpect(jsonPath("$.acceptedOrderIds").value(1))
                .andExpect(jsonPath("$.bids[0].priceTicks").value(100))
                .andExpect(jsonPath("$.bestBid.orderId").value("B1"));
    }

    @Test void crossingUsesRestingPriceAndPreservesRemainingQuantity() throws Exception {
        submit("B1", "BUY", 100, 4);
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(order("S1", "SELL", 99, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(2))
                .andExpect(jsonPath("$.executedQuantity").value(3))
                .andExpect(jsonPath("$.restingQuantity").value(0))
                .andExpect(jsonPath("$.trades[0].makerOrderId").value("B1"))
                .andExpect(jsonPath("$.trades[0].takerOrderId").value("S1"))
                .andExpect(jsonPath("$.trades[0].priceTicks").value(100))
                .andExpect(jsonPath("$.trades[0].quantity").value(3));
        mvc.perform(get("/api/v1/orders/B1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.remainingQuantity").value(1));
    }

    @Test void samePriceFifoSurvivesSpringTransport() throws Exception {
        submit("B1", "BUY", 100, 4);
        submit("B2", "BUY", 100, 2);
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(order("S1", "SELL", 100, 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trades.length()").value(2))
                .andExpect(jsonPath("$.trades[0].makerOrderId").value("B1"))
                .andExpect(jsonPath("$.trades[0].quantity").value(4))
                .andExpect(jsonPath("$.trades[1].makerOrderId").value("B2"))
                .andExpect(jsonPath("$.trades[1].quantity").value(1));
        mvc.perform(get("/api/v1/book"))
                .andExpect(jsonPath("$.bestBid.orderId").value("B2"))
                .andExpect(jsonPath("$.bestBid.remainingQuantity").value(1));
    }

    @Test void cancellationRemovesOnlyUnfilledRemainder() throws Exception {
        submit("B1", "BUY", 100, 5);
        submit("S1", "SELL", 99, 3);
        mvc.perform(post("/api/v1/orders/B1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(3))
                .andExpect(jsonPath("$.decision").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledQuantity").value(2))
                .andExpect(jsonPath("$.cancelledOrder.orderId").value("B1"));
        mvc.perform(get("/api/v1/orders/B1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test void duplicateOrderIdIsSequencedBusinessRejectionNotBadJson() throws Exception {
        submit("DUP", "BUY", 100, 1);
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(order("DUP", "BUY", 101, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sequence").value(2))
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.code").value("DUPLICATE_ORDER_ID"));
    }

    @Test void malformedDomainValuesAreUnsequenced422() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(order("B1", "BUY", 0, 4)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRICE"));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(order("B1", "HOLD", 100, 4)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_SIDE"));
        mvc.perform(get("/api/v1/book"))
                .andExpect(jsonPath("$.asOfSequence").value(0));
    }

    @Test void wrongTypeOrExtraFieldsAre400AndDoNotReachTheWriter() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrument\":\"DEMO\",\"orderId\":\"B1\",\"side\":\"BUY\",\"priceTicks\":\"100\",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FIELD_TYPE"));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(order("B2", "BUY", 100, 1).replace("}", ",\"surprise\":true}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FIELDS"));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrument\":\"DEMO\",\"orderId\":\"B3\",\"side\":\"BUY\",\"priceTicks\":100.5,\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FIELD_TYPE"));
        mvc.perform(get("/api/v1/book"))
                .andExpect(jsonPath("$.asOfSequence").value(0));
    }


    @Test void missingCancelHasOrderedRejectionButNoBookMutation() throws Exception {
        mvc.perform(post("/api/v1/orders/UNKNOWN/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.code").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/api/v1/book"))
                .andExpect(jsonPath("$.asOfSequence").value(1))
                .andExpect(jsonPath("$.restingOrders").value(0));
    }

    @Test void incompatibleInstrumentIsOrderedBusinessRejection() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrument\":\"OTHER\",\"orderId\":\"B1\",\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.rejection.code").value("INSTRUMENT_MISMATCH"));
    }

    @Test void malformedQueryCannotBeSilentlyIgnored() throws Exception {
        mvc.perform(get("/api/v1/book?depth=0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/v1/book?depth=1&depth=3"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/v1/book?wat=1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
    }

    @Test void invalidJsonAndUnsupportedMediaTypeAreExplicitErrors() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{not-json}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_JSON"));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.TEXT_PLAIN)
                .content(order("B1", "BUY", 100, 1)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
