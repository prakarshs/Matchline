package dev.matchline.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test void parsesFlatOrder() {
        assertEquals(Map.of("instrument", "DEMO", "side", "BUY", "priceTicks", 100L,
                "quantity", 3L), Json.parseObject("{\"instrument\":\"DEMO\",\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":3}"));
    }

    @Test void acceptsValidStringEscapes() {
        assertEquals("quote\" slash/ newline\n", Json.parseObject("{\"value\":\"quote\\\" slash\\/ newline\\n\"}").get("value"));
        assertEquals("A", Json.parseObject("{\"letter\":\"\\u0041\"}").get("letter"));
    }

    @Test void encodesNestedServerResponsesWithEscaping() {
        String encoded = Json.encode(ApiView.of("books", List.of(ApiView.of("name", "A\"B", "count", 2)),
                "empty", null));
        assertEquals("{\"books\":[{\"name\":\"A\\\"B\",\"count\":2}],\"empty\":null}", encoded);
    }

    @Test void rejectsDuplicateKeysEvenWhenFirstValueIsNull() {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":null,\"a\":1}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":1,\"a\":2}"));
    }

    @Test void rejectsNestedInputs() {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":{\"b\":1}}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":[1,2]}"));
    }

    @Test void rejectsInvalidNumbersAndTrailingData() {
        for (String invalid : List.of("{\"x\":1.5}", "{\"x\":1e3}", "{\"x\":01}",
                "{\"x\":9223372036854775808}", "{\"x\":-}", "{\"x\":1} {}")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parseObject(invalid));
        }
    }

    @Test void rejectsUnterminatedAndMalformedStrings() {
        for (String invalid : List.of("{\"x\":\"unfinished}", "{\"x\":\"\\q\"}",
                "{\"x\":\"\\u00XY\"}", "{\"x\":\"line\nnext\"}")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parseObject(invalid));
        }
    }

    @Test void supportsBooleansNullNegativeAndWhitespace() {
        Map<String, Object> parsed = Json.parseObject("  { \"a\": true, \"b\": false, \"c\": null, \"d\": -1 } \n");
        assertEquals(true, parsed.get("a"));
        assertEquals(false, parsed.get("b"));
        assertEquals(null, parsed.get("c"));
        assertEquals(-1L, parsed.get("d"));
    }
}
