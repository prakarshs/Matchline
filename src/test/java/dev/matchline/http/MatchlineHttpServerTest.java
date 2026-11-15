package dev.matchline.http;

import dev.matchline.journal.DurabilityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class MatchlineHttpServerTest {
    @TempDir Path directory;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private HttpConfig config(String origin, String token, Path journal, Path snapshot) {
        return new HttpConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), origin,
                token, journal, snapshot, DurabilityPolicy.SYNC_EACH_RECORD, 128);
    }

    private record Response(int code, String body, HttpResponse<String> raw) { }

    private Response request(MatchlineHttpServer server, String method, String path, String body) throws Exception {
        return request(server, method, path, body, null, null);
    }

    private Response request(MatchlineHttpServer server, String method, String path, String body,
                             String origin, String auth) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.address().getPort() + path))
                .timeout(Duration.ofSeconds(10));
        if (body != null) builder.header("Content-Type", "application/json");
        if (origin != null) builder.header("Origin", origin);
        if (auth != null) builder.header("Authorization", auth);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response);
    }

    private static String place(String id, String side, long price, long quantity) {
        return "{\"instrument\":\"DEMO\",\"orderId\":\"" + id
                + "\",\"side\":\"" + side + "\",\"priceTicks\":" + price
                + ",\"quantity\":" + quantity + "}";
    }

    @Test void healthAndOpenApiServeOverRealHttp() throws Exception {
        try (var server = new MatchlineHttpServer(config(null, null, null, null))) {
            server.start();
            Response health = request(server, "GET", "/api/v1/health", null);
            assertEquals(200, health.code());
            assertTrue(health.body().contains("\"status\":\"UP\""));
            assertEquals("nosniff", health.raw().headers().firstValue("X-Content-Type-Options").orElseThrow());
            Response spec = request(server, "GET", "/openapi.yaml", null);
            assertEquals(200, spec.code());
            assertTrue(spec.body().contains("openapi: 3.0.3"));
            assertTrue(spec.body().contains("/api/v1/demo/reset:"));
        }
    }

    @Test void makerPriceFifoAndPartialFillAreExposedOverHttp() throws Exception {
        try (var server = new MatchlineHttpServer(config(null, null, null, null))) {
            server.start();
            assertEquals(200, request(server, "POST", "/api/v1/orders", place("S1", "SELL", 100, 2)).code());
            assertEquals(200, request(server, "POST", "/api/v1/orders", place("S2", "SELL", 100, 3)).code());
            Response buy = request(server, "POST", "/api/v1/orders", place("B1", "BUY", 103, 4));
            assertEquals(200, buy.code());
            assertTrue(buy.body().contains("\"executedQuantity\":4"));
            assertTrue(buy.body().contains("\"restingQuantity\":0"));
            assertTrue(buy.body().contains("\"makerOrderId\":\"S1\""));
            assertTrue(buy.body().contains("\"makerOrderId\":\"S2\""));
            assertTrue(buy.body().indexOf("\"makerOrderId\":\"S1\"")
                    < buy.body().indexOf("\"makerOrderId\":\"S2\""));
            assertTrue(buy.body().contains("\"priceTicks\":100"));
            Response book = request(server, "GET", "/api/v1/book?depth=5", null);
            assertEquals(200, book.code());
            assertTrue(book.body().contains("\"asOfSequence\":3"));
            assertTrue(book.body().contains("\"quantity\":1"));
            assertTrue(book.body().contains("\"orderId\":\"S2\""));
            Response trades = request(server, "GET", "/api/v1/trades?limit=10", null);
            assertEquals(200, trades.code());
            assertTrue(trades.body().contains("\"fillIndex\":1"));
            assertTrue(trades.body().contains("\"fillIndex\":2"));
            assertTrue(trades.body().contains("\"source\":\"PROCESS_MEMORY\""));
        }
    }

    @Test void orderedCancellationRetainsHistoryAndRejectsReuse() throws Exception {
        try (var server = new MatchlineHttpServer(config(null, null, null, null))) {
            server.start();
            assertEquals(200, request(server, "POST", "/api/v1/orders", place("B1", "BUY", 100, 9)).code());
            Response cancel = request(server, "POST", "/api/v1/orders/B1/cancel", null);
            assertEquals(200, cancel.code());
            assertTrue(cancel.body().contains("\"decision\":\"CANCELLED\""));
            assertTrue(cancel.body().contains("\"cancelledQuantity\":9"));
            assertEquals(404, request(server, "GET", "/api/v1/orders/B1", null).code());
            Response duplicate = request(server, "POST", "/api/v1/orders", place("B1", "BUY", 100, 3));
            assertEquals(409, duplicate.code());
            assertTrue(duplicate.body().contains("\"sequence\":3"));
            assertTrue(duplicate.body().contains("DUPLICATE_ORDER_ID"));
            Response missing = request(server, "POST", "/api/v1/orders/B1/cancel", null);
            assertEquals(409, missing.code());
            assertTrue(missing.body().contains("ORDER_NOT_FOUND"));
            assertTrue(request(server, "GET", "/api/v1/events?limit=10", null)
                    .body().contains("\"decision\":\"REJECTED\""));
        }
    }

    @Test void invalidInputsDoNotConsumeSequence() throws Exception {
        try (var server = new MatchlineHttpServer(config(null, null, null, null))) {
            server.start();
            for (String invalid : new String[]{"{}", "{\"a\":null,\"a\":2}",
                    place("B1", "BUY", 0, 2), place("B1", "BUY", 100, 0),
                    place("B1", "BOTH", 100, 1), "{\"instrument\":\"DEMO\",\"orderId\":\"B1\"," +
                    "\"side\":\"BUY\",\"priceTicks\":1.5,\"quantity\":1}"}) {
                assertTrue(request(server, "POST", "/api/v1/orders", invalid).code() >= 400);
            }
            assertTrue(request(server, "GET", "/api/v1/status", null).body()
                    .contains("\"lastAdmittedSequence\":0"));
            Response accepted = request(server, "POST", "/api/v1/orders", place("B1", "BUY", 100, 1));
            assertEquals(200, accepted.code());
            assertTrue(accepted.body().contains("\"sequence\":1"));
        }
    }

    @Test void boundedBodyMediaTypesAndQueriesProduceStructuredErrors() throws Exception {
        try (var server = new MatchlineHttpServer(config(null, null, null, null))) {
            server.start();
            var tooBig = request(server, "POST", "/api/v1/orders", " ".repeat(4097));
            assertEquals(413, tooBig.code());
            assertTrue(tooBig.body().contains("BODY_TOO_LARGE"));
            assertEquals(400, request(server, "GET", "/api/v1/book?depth=0", null).code());
            assertEquals(400, request(server, "GET", "/api/v1/trades?limit=1&limit=2", null).code());
            assertEquals(404, request(server, "GET", "/unknown", null).code());
            assertEquals(405, request(server, "PUT", "/api/v1/orders", "{}").code());
            HttpRequest raw = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + server.address().getPort() + "/api/v1/orders"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            assertEquals(415, client.send(raw, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void invalidUtf8IsRejectedWithoutAdmission() throws Exception {
        try (var server = new MatchlineHttpServer(config(null, null, null, null))) {
            server.start();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + server.address().getPort() + "/api/v1/orders"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{'{', '"', (byte) 0xC3, '"', '}'}))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("INVALID_JSON"));
            assertTrue(request(server, "GET", "/api/v1/status", null).body()
                    .contains("\"lastAdmittedSequence\":0"));
        }
    }

    @Test void resetIsUnavailableByDefault() throws Exception {
        try (var server = new MatchlineHttpServer(config(null, null, null, null))) {
            server.start();
            assertEquals(404, request(server, "POST", "/api/v1/demo/reset", null).code());
        }
    }

    @Test void authorizedVolatileResetCreatesNewBookGeneration() throws Exception {
        String token = "nL9aH0dr8qB7P3xC2kY6wT4eV5zS1uQ0";
        try (var server = new MatchlineHttpServer(config(null, token, null, null))) {
            server.start();
            assertEquals(200, request(server, "POST", "/api/v1/orders", place("B1", "BUY", 100, 3)).code());
            assertEquals(403, request(server, "POST", "/api/v1/demo/reset", null, null, "Bearer wrong").code());
            assertTrue(request(server, "GET", "/api/v1/book", null).body().contains("\"restingOrders\":1"));
            Response reset = request(server, "POST", "/api/v1/demo/reset", null, null, "Bearer " + token);
            assertEquals(200, reset.code());
            assertTrue(reset.body().contains("\"generation\":2"));
            assertTrue(request(server, "GET", "/api/v1/book", null).body().contains("\"restingOrders\":0"));
            assertEquals(200, request(server, "POST", "/api/v1/orders", place("B1", "BUY", 100, 3)).code());
            assertTrue(request(server, "GET", "/api/v1/status", null).body().contains("\"generation\":2"));
        }
    }

    @Test void corsHasExactOriginAndNoWildcard() throws Exception {
        String allowed = "https://portfolio.example";
        try (var server = new MatchlineHttpServer(config(allowed, null, null, null))) {
            server.start();
            assertEquals(200, request(server, "GET", "/api/v1/book", null, allowed, null).code());
            Response allowedResponse = request(server, "GET", "/api/v1/book", null, allowed, null);
            assertEquals(allowed, allowedResponse.raw().headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertEquals(403, request(server, "GET", "/api/v1/book", null, "https://attacker.example", null).code());
            Response without = request(server, "GET", "/api/v1/book", null);
            assertFalse(without.raw().headers().firstValue("Access-Control-Allow-Origin").isPresent());
        }
    }

    @Test void journalRestartRestoresBookAndPreservesSequenceWithoutReplayingTape() throws Exception {
        Path journal = directory.resolve("session.mlj");
        HttpConfig config = config(null, null, journal, null);
        try (var first = new MatchlineHttpServer(config)) {
            first.start();
            Response placed = request(first, "POST", "/api/v1/orders", place("B1", "BUY", 100, 4));
            assertEquals(200, placed.code());
            assertTrue(placed.body().contains("\"durable\":true"));
            assertEquals(200, request(first, "POST", "/api/v1/orders", place("S1", "SELL", 100, 2)).code());
        }
        try (var restarted = new MatchlineHttpServer(config)) {
            restarted.start();
            Response restored = request(restarted, "GET", "/api/v1/book", null);
            assertEquals(200, restored.code());
            assertTrue(restored.body().contains("\"asOfSequence\":2"));
            assertTrue(restored.body().contains("\"remainingQuantity\":2"));
            assertTrue(request(restarted, "GET", "/api/v1/trades", null).body().contains("\"trades\":[]"));
            Response recovery = request(restarted, "GET", "/api/v1/recovery", null);
            assertTrue(recovery.body().contains("\"recoveredExistingJournal\":true"));
            assertTrue(recovery.body().contains("\"recoveredSequence\":2"));
            assertTrue(request(restarted, "POST", "/api/v1/orders", place("B2", "BUY", 99, 1))
                    .body().contains("\"sequence\":3"));
        }
    }

    @Test void journalBackedBookCannotBeResetEvenWithToken() throws Exception {
        String token = "6eN8fR4uP0eZ1cY3tA7dM2nK9wS5vH0b";
        HttpConfig config = config(null, token, directory.resolve("journal.mlj"), null);
        try (var server = new MatchlineHttpServer(config)) {
            server.start();
            assertEquals(200, request(server, "POST", "/api/v1/orders", place("B1", "BUY", 100, 2)).code());
            Response refused = request(server, "POST", "/api/v1/demo/reset", null, null, "Bearer " + token);
            assertEquals(409, refused.code());
            assertTrue(refused.body().contains("PERSISTED_RESET_FORBIDDEN"));
            assertTrue(request(server, "GET", "/api/v1/book", null).body().contains("\"restingOrders\":1"));
        }
    }

    @Test void concurrentHttpSubmissionsAreAssignedUniqueGaplessSequences() throws Exception {
        try (var server = new MatchlineHttpServer(config(null, null, null, null))) {
            server.start();
            var submitted = new java.util.ArrayList<CompletableFuture<Response>>();
            for (int i = 0; i < 32; i++) {
                int n = i;
                submitted.add(CompletableFuture.supplyAsync(() -> {
                    try { return request(server, "POST", "/api/v1/orders", place("B" + n, "BUY", 90, 1)); }
                    catch (Exception exception) { throw new RuntimeException(exception); }
                }));
            }
            Set<Long> sequences = new HashSet<>();
            for (var task : submitted) {
                Response response = task.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(200, response.code());
                String prefix = "\"sequence\":";
                int offset = response.body().indexOf(prefix) + prefix.length();
                int end = response.body().indexOf(',', offset);
                sequences.add(Long.parseLong(response.body().substring(offset, end)));
            }
            assertEquals(32, sequences.size());
            for (long i = 1; i <= 32; i++) assertTrue(sequences.contains(i));
            assertTrue(request(server, "GET", "/api/v1/book", null).body().contains("\"restingOrders\":32"));
            assertTrue(request(server, "GET", "/api/v1/book", null).body().contains("\"asOfSequence\":32"));
        }
    }
    @Test void corruptJournalRequiresExplicitOfflineRepairBeforeHttpStartup() throws Exception {
        Path journal = directory.resolve("needs-repair.mlj");
        HttpConfig config = config(null, null, journal, null);
        try (var first = new MatchlineHttpServer(config)) {
            first.start();
            assertEquals(200, request(first, "POST", "/api/v1/orders", place("B1", "BUY", 100, 1)).code());
        }
        long cleanSize = java.nio.file.Files.size(journal);
        try (var channel = java.nio.channels.FileChannel.open(journal,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(cleanSize);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{1, 2}));
        }
        assertThrows(java.io.IOException.class, () -> new MatchlineHttpServer(config));
        assertEquals(cleanSize + 2, java.nio.file.Files.size(journal));
        var repaired = dev.matchline.journal.FileCommandJournal.scan(journal,
                dev.matchline.journal.TailPolicy.TRUNCATE_INCOMPLETE_TAIL);
        assertEquals(2L, repaired.truncatedBytes());
        try (var after = new MatchlineHttpServer(config)) {
            after.start();
            assertTrue(request(after, "GET", "/api/v1/book", null).body()
                    .contains("\"restingOrders\":1"));
        }
    }

    @Test void httpRestartRestoresAtVerifiedSnapshotBoundary() throws Exception {
        Path journal = directory.resolve("checkpoint.mlj");
        Path snapshot = directory.resolve("checkpoint.mls");
        HttpConfig config = config(null, null, journal, snapshot);
        try (var first = new MatchlineHttpServer(config)) {
            first.start();
            for (int i = 1; i <= 100; i++) {
                Response reply = request(first, "POST", "/api/v1/orders", place("B" + i, "BUY", 90, 1));
                assertEquals(200, reply.code());
            }
        }
        assertTrue(java.nio.file.Files.exists(snapshot));
        try (var restarted = new MatchlineHttpServer(config)) {
            restarted.start();
            Response recovery = request(restarted, "GET", "/api/v1/recovery", null);
            assertEquals(200, recovery.code());
            assertTrue(recovery.body().contains("\"snapshotSequence\":100"));
            assertTrue(recovery.body().contains("\"replayedDecisions\":0"));
            assertTrue(request(restarted, "GET", "/api/v1/book", null).body()
                    .contains("\"restingOrders\":100"));
            assertTrue(request(restarted, "POST", "/api/v1/orders", place("S1", "SELL", 90, 1))
                    .body().contains("\"sequence\":101"));
        }
    }

}
