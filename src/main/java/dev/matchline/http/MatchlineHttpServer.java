package dev.matchline.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.matchline.book.LimitOrderBook;
import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.OrderValidationException;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import dev.matchline.journal.DurabilityPolicy;
import dev.matchline.journal.FileCommandJournal;
import dev.matchline.recovery.RecoveryReport;
import dev.matchline.recovery.SnapshotSchedule;
import dev.matchline.sequencer.CommandDecision;
import dev.matchline.sequencer.CommandTicket;
import dev.matchline.sequencer.RecoveredSequencer;
import dev.matchline.sequencer.SequencedResult;
import dev.matchline.sequencer.SingleWriterSequencer;
import dev.matchline.observability.HttpMetrics;
import dev.matchline.observability.MetricsFormat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Java 21 HTTP adapter for one DEMO instrument. All book operations, including
 * reads, enter the existing bounded single-writer sequencer. No mutable book
 * escapes the writer. Per-process trade/event tape is NOT a durable event log.
 *
 * <p>Default bind is loopback; no cross-origin browser access and no reset until
 * explicitly configured. No authentication for order placement: public bind must
 * only be used behind appropriate network protection and rate limiting.
 */
public final class MatchlineHttpServer implements AutoCloseable {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");
    private static final int MAX_BODY = 4_096;
    private static final long WAIT_SECONDS = 5;
    private static final int MAX_RECENT = 100;
    private static final int MAX_IN_FLIGHT = 64;
    private final HttpConfig config;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantReadWriteLock sessionLock = new ReentrantReadWriteLock(true);
    private final Semaphore concurrency = new Semaphore(MAX_IN_FLIGHT);
    private final RateLimit rateLimit = new RateLimit();
    private final HttpMetrics httpMetrics = new HttpMetrics();
    private final RecentTape tape;
    private SingleWriterSequencer engine;
    private RecoveryReport recovered;
    private boolean recoveredExistingJournal;
    private long generation = 1;
    private volatile boolean started;
    private volatile boolean closed;

    public MatchlineHttpServer(HttpConfig config) throws IOException {
        this.config = Objects.requireNonNull(config, "config");
        this.tape = new RecentTape(config.journalPath() != null
                && config.durability() == DurabilityPolicy.SYNC_EACH_RECORD);
        try {
            server = HttpServer.create(config.bind(), 0);
        } catch (IOException cannotBind) {
            executor.close();
            throw cannotBind;
        }
        try {
            EngineStart runtime = newEngine(config);
            this.engine = runtime.sequencer();
            this.recovered = runtime.report();
            this.recoveredExistingJournal = runtime.existingJournal();
            server.createContext("/", this::dispatch);
            server.setExecutor(executor);
        } catch (IOException | RuntimeException failed) {
            server.stop(0);
            if (engine != null) engine.close();
            executor.close();
            throw failed;
        }
    }

    /** Network listener starts only when explicitly requested. */
    public synchronized void start() {
        if (started || closed) throw new IllegalStateException("HTTP service already started or closed");
        server.start();
        started = true;
    }

    public InetSocketAddress address() { return server.getAddress(); }

    private static EngineStart newEngine(HttpConfig config) throws IOException {
        Path journalPath = config.journalPath();
        if (journalPath == null) {
            return new EngineStart(new SingleWriterSequencer(DEMO, config.queueCapacity()), null, false);
        }
        SnapshotSchedule schedule = config.snapshotPath() == null
                ? null : new SnapshotSchedule(config.snapshotPath(), 100);
        if (Files.exists(journalPath)) {
            RecoveredSequencer restored = SingleWriterSequencer.recover(journalPath, DEMO,
                    config.queueCapacity(), LimitOrderBook.DEFAULT_MAX_RESTING_ORDERS,
                    LimitOrderBook.DEFAULT_MAX_ORDER_IDS, config.durability(), schedule);
            return new EngineStart(restored.sequencer(), restored.report(), true);
        }
        FileCommandJournal journal = FileCommandJournal.createNew(journalPath, DEMO, config.durability());
        try {
            SingleWriterSequencer sequencer = schedule == null
                    ? new SingleWriterSequencer(DEMO, config.queueCapacity(),
                            LimitOrderBook.DEFAULT_MAX_RESTING_ORDERS,
                            LimitOrderBook.DEFAULT_MAX_ORDER_IDS, journal)
                    : new SingleWriterSequencer(DEMO, config.queueCapacity(),
                            LimitOrderBook.DEFAULT_MAX_RESTING_ORDERS,
                            LimitOrderBook.DEFAULT_MAX_ORDER_IDS, journal, schedule);
            return new EngineStart(sequencer, null, false);
        } catch (RuntimeException failed) {
            journal.close();
            throw failed;
        }
    }

    private record EngineStart(SingleWriterSequencer sequencer, RecoveryReport report,
                               boolean existingJournal) { }

    private void dispatch(HttpExchange exchange) throws IOException {
        long startedNanos = System.nanoTime();
        try {
            commonHeaders(exchange);
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod();
            if (config.allowedOrigin() != null && exchange.getRequestHeaders().getFirst("Origin") != null
                    && !config.allowedOrigin().equals(exchange.getRequestHeaders().getFirst("Origin"))) {
                throw new ApiError(403, "ORIGIN_DENIED", "origin not permitted");
            }
            if ("OPTIONS".equals(method)) {
                if (config.allowedOrigin() == null || exchange.getRequestHeaders().getFirst("Origin") == null) {
                    throw new ApiError(403, "CORS_DISABLED", "cross-origin requests are not enabled");
                }
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
                exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
                exchange.sendResponseHeaders(204, -1);
                httpMetrics.response(204);
                return;
            }
            if (!"GET".equals(method) && !"POST".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
                throw new ApiError(405, "METHOD_NOT_ALLOWED", "only GET and POST are supported");
            }
            if (!concurrency.tryAcquire()) throw new ApiError(503, "SERVER_BUSY", "too many simultaneous requests");
            try {
                if (!"/api/v1/health".equals(path) && !"/health".equals(path) && !rateLimit.allow()) {
                    exchange.getResponseHeaders().set("Retry-After", "1");
                    throw new ApiError(429, "HTTP_RATE_LIMIT", "global HTTP request budget exhausted");
                }
                route(exchange, method, path);
            } finally {
                concurrency.release();
            }
        } catch (ApiError rejected) {
            sendError(exchange, rejected.status, rejected.code, rejected.getMessage(), rejected.sequence);
        } catch (OrderValidationException validation) {
            int status = switch (validation.code()) {
                case INGRESS_OVERLOADED -> 429;
                case INGRESS_CLOSED -> 503;
                default -> 422;
            };
            sendError(exchange, status, validation.code().name(), validation.getMessage(), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sendError(exchange, 503, "INTERRUPTED", "request interrupted", null);
        } catch (Exception unexpected) {
            // Avoid leaking filesystem paths, exception class names, tokens, or stack traces.
            sendError(exchange, 500, "INTERNAL_ERROR", "request could not be completed", null);
        } finally {
            httpMetrics.duration(System.nanoTime() - startedNanos);
            exchange.close();
        }
    }

    private void route(HttpExchange x, String method, String path) throws Exception {
        if (("/api/v1/health".equals(path) || "/health".equals(path)) && "GET".equals(method)) {
            sessionLock.readLock().lock();
            try {
                boolean healthy = !closed && engine.accepting() && engine.fatalFailure().isEmpty();
                send(x, healthy ? 200 : 503, ApiView.of("status", healthy ? "UP" : "DOWN",
                        "instrument", "DEMO"));
            } finally { sessionLock.readLock().unlock(); }
            return;
        }
        if (UiAssets.serves(path) && "GET".equals(method)) {
            UiAssets.send(x, path, config.embedOrigin());
            httpMetrics.response(200);
            return;
        }
        if ("/openapi.yaml".equals(path) && "GET".equals(method)) {
            try (InputStream resource = MatchlineHttpServer.class.getResourceAsStream("/openapi.yaml")) {
                if (resource == null) throw new ApiError(500, "SPEC_MISSING", "OpenAPI resource not packaged");
                byte[] bytes = resource.readAllBytes();
                x.getResponseHeaders().set("Content-Type", "application/yaml; charset=utf-8");
                x.sendResponseHeaders(200, bytes.length);
                httpMetrics.response(200);
                x.getResponseBody().write(bytes);
            }
            return;
        }
        if (("/api/v1/metrics".equals(path) || "/metrics".equals(path)) && "GET".equals(method)) {
            sessionLock.readLock().lock();
            try {
                if (closed) throw new ApiError(503, "SERVICE_CLOSED", "HTTP service is closed");
                var engineMetrics = engine.metricsSnapshot();
                var http = httpMetrics.snapshot();
                if ("/metrics".equals(path)) {
                    byte[] data = MetricsFormat.prometheus(engineMetrics, http, generation,
                            engine.queuedWorkCount(), engine.queueCapacity())
                            .getBytes(StandardCharsets.UTF_8);
                    x.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                    x.sendResponseHeaders(200, data.length);
                    httpMetrics.response(200);
                    x.getResponseBody().write(data);
                } else {
                    send(x, 200, MetricsFormat.asJson(engineMetrics, http, generation,
                            engine.lastProcessedSequence(), engine.queuedWorkCount(), engine.queueCapacity()));
                }
            } finally { sessionLock.readLock().unlock(); }
            return;
        }
        if ("/api/v1/demo/reset".equals(path) && "POST".equals(method)) {
            reset(x);
            return;
        }
        sessionLock.readLock().lock();
        try {
            if (closed) throw new ApiError(503, "SERVICE_CLOSED", "HTTP service is closed");
            if ("/api/v1/orders".equals(path) && "POST".equals(method)) {
                Map<String, Object> payload = payload(x);
                exactFields(payload, Set.of("instrument", "orderId", "side", "priceTicks", "quantity"));
                PlaceLimitOrder command = new PlaceLimitOrder(
                        new InstrumentId(string(payload, "instrument")),
                        new OrderId(string(payload, "orderId")), side(payload),
                        new PriceTicks(integer(payload, "priceTicks")),
                        new OrderQuantity(integer(payload, "quantity")));
                complete(x, command);
                return;
            }
            if (path.startsWith("/api/v1/orders/") && path.endsWith("/cancel")
                    && "POST".equals(method)) {
                String id = path.substring("/api/v1/orders/".length(), path.length() - "/cancel".length());
                if (id.isEmpty() || id.indexOf('/') >= 0 || id.indexOf('%') >= 0)
                    throw new ApiError(404, "NOT_FOUND", "unknown endpoint");
                complete(x, new CancelOrder(DEMO, new OrderId(id)));
                return;
            }
            if (path.startsWith("/api/v1/orders/") && "GET".equals(method)) {
                String id = path.substring("/api/v1/orders/".length());
                if (id.isEmpty() || id.indexOf('/') >= 0 || id.indexOf('%') >= 0)
                    throw new ApiError(404, "NOT_FOUND", "unknown endpoint");
                var found = await(engine.find(new OrderId(id)));
                if (found.isEmpty()) throw new ApiError(404, "ORDER_NOT_FOUND", "no resting order with this ID");
                send(x, 200, ApiView.of("instrument", "DEMO", "order", ApiView.order(found.get())));
                return;
            }
            if ("/api/v1/book".equals(path) && "GET".equals(method)) {
                int depth = queryInt(x, "depth", 10, 1, LimitOrderBook.MAX_DEPTH_LEVELS);
                send(x, 200, ApiView.book(await(engine.inspect(depth))));
                return;
            }
            if ("/api/v1/trades".equals(path) && "GET".equals(method)) {
                int limit = queryInt(x, "limit", 50, 1, MAX_RECENT);
                // Ordered barrier: historical HTTP-completed outcomes only, not a durable trade log.
                var observedAt = await(engine.inspect(1));
                send(x, 200, ApiView.of("instrument", "DEMO", "observedThroughSequence",
                        observedAt.lastProcessedSequence(), "source", "PROCESS_MEMORY",
                        "trades", tape.events(limit, true)));
                return;
            }
            if ("/api/v1/events".equals(path) && "GET".equals(method)) {
                int limit = queryInt(x, "limit", 50, 1, MAX_RECENT);
                var observedAt = await(engine.inspect(1));
                send(x, 200, ApiView.of("instrument", "DEMO", "observedThroughSequence",
                        observedAt.lastProcessedSequence(), "source", "PROCESS_MEMORY",
                        "events", tape.events(limit, false)));
                return;
            }
            if ("/api/v1/status".equals(path) && "GET".equals(method)) {
                send(x, 200, status());
                return;
            }
            if ("/api/v1/recovery".equals(path) && "GET".equals(method)) {
                send(x, 200, ApiView.of("instrument", "DEMO", "journalConfigured", config.journalPath() != null,
                        "journalFormatVersion", config.journalPath() == null ? null : 1,
                        "durabilityPolicy", config.journalPath() == null ? "NONE" : config.durability().name(),
                        "recoveredExistingJournal", recoveredExistingJournal,
                        "lastRecovery", recovered == null ? null : recoveryReport(recovered),
                        "lastProcessedSequence", engine.lastProcessedSequence(),
                        "note", "Verified reconstruction happens at startup. Live tape is not replay history."));
                return;
            }
            throw new ApiError(404, "NOT_FOUND", "unknown endpoint");
        } finally { sessionLock.readLock().unlock(); }
    }

    private Map<String, Object> status() {
        return ApiView.of("application", "MATCHLINE", "instrument", "DEMO", "generation", generation,
                "accepting", engine.accepting(), "healthy", !closed && engine.accepting()
                        && engine.fatalFailure().isEmpty(),
                "lastAdmittedSequence", engine.lastAdmittedSequence(),
                "lastProcessedSequence", engine.lastProcessedSequence(),
                "queuedWork", engine.queuedWorkCount(), "queueCapacity", engine.queueCapacity(),
                "journalConfigured", config.journalPath() != null,
                "durabilityPolicy", config.journalPath() == null ? "NONE" : config.durability().name(),
                "tapeRecordedCommands", tape.size(), "tapeCapacity", RecentTape.MAX_COMMANDS, "tradeTapeCapacity", RecentTape.MAX_TRADES,
                "resetEnabled", config.resetToken() != null && config.journalPath() == null);
    }

    private static Map<String, Object> recoveryReport(RecoveryReport r) {
        return ApiView.of("snapshotSequence", r.snapshotSequence(),
                "replayedDecisions", r.replayedDecisions(), "recoveredSequence", r.recoveredSequence(),
                "resolvedPendingCommand", r.resolvedPendingCommand());
    }

    private void complete(HttpExchange x, dev.matchline.domain.OrderCommand command) throws Exception {
        CommandTicket ticket = engine.submit(command);
        SequencedResult result;
        try {
            result = ticket.completion().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException unknown) {
            throw new ApiError(504, "OUTCOME_UNKNOWN", "command was admitted; outcome not confirmed; inspect status before retrying",
                    ticket.sequence());
        } catch (ExecutionException failure) {
            throw new ApiError(503, "WRITER_FAILURE", "writer stopped before acknowledgement; outcome may be unknown",
                    ticket.sequence());
        }
        tape.record(result);
        Map<String, Object> output = ApiView.decision(result);
        output.put("durable", config.journalPath() != null
                && config.durability() == DurabilityPolicy.SYNC_EACH_RECORD);
        send(x, result.decision() instanceof CommandDecision.Rejected ? 409 : 200, output);
    }

    private void reset(HttpExchange x) throws IOException {
        String token = config.resetToken();
        if (token == null) throw new ApiError(404, "NOT_FOUND", "unknown endpoint");
        String supplied = x.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + token;
        if (supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiError(403, "RESET_FORBIDDEN", "valid reset bearer token required");
        }
        if (config.journalPath() != null) {
            throw new ApiError(409, "PERSISTED_RESET_FORBIDDEN",
                    "journal-backed books cannot be reset through HTTP");
        }
        sessionLock.writeLock().lock();
        try {
            if (closed) throw new ApiError(503, "SERVICE_CLOSED", "HTTP service is closed");
            // Block all ingress while closing; wait for every previously admitted command.
            engine.close();
            engine = new SingleWriterSequencer(DEMO, config.queueCapacity());
            tape.clear();
            generation++;
            send(x, 200, ApiView.of("reset", true, "generation", generation,
                    "instrument", "DEMO", "sequence", 0));
        } finally { sessionLock.writeLock().unlock(); }
    }

    private static void exactFields(Map<String, Object> fields, Set<String> expected) {
        if (!fields.keySet().equals(expected))
            throw new ApiError(400, "INVALID_FIELDS", "required fields: instrument, orderId, side, priceTicks, quantity; no extras");
    }

    private static String string(Map<String, Object> fields, String key) {
        if (!(fields.get(key) instanceof String value))
            throw new ApiError(400, "INVALID_FIELD_TYPE", key + " must be a JSON string");
        return value;
    }

    private static long integer(Map<String, Object> fields, String key) {
        if (!(fields.get(key) instanceof Long value))
            throw new ApiError(400, "INVALID_FIELD_TYPE", key + " must be an integer");
        return value;
    }

    private static Side side(Map<String, Object> fields) {
        String text = string(fields, "side");
        try { return Side.valueOf(text); }
        catch (IllegalArgumentException invalid) {
            throw new OrderValidationException(RejectionCode.INVALID_SIDE, "side must be BUY or SELL");
        }
    }

    private static Map<String, Object> payload(HttpExchange x) throws IOException {
        String contentType = x.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")) {
            throw new ApiError(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json; charset=utf-8");
        }
        String length = x.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length) > MAX_BODY) throw new ApiError(413, "BODY_TOO_LARGE", "request body exceeds 4096 bytes");
            } catch (NumberFormatException invalid) {
                throw new ApiError(400, "INVALID_CONTENT_LENGTH", "invalid Content-Length");
            }
        }
        byte[] bytes = x.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new ApiError(413, "BODY_TOO_LARGE", "request body exceeds 4096 bytes");
        try {
            String body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return Json.parseObject(body);
        } catch (CharacterCodingException | IllegalArgumentException invalid) {
            throw new ApiError(400, "INVALID_JSON", "request must be a valid flat JSON object");
        }
    }

    private static int queryInt(HttpExchange x, String key, int fallback, int min, int max) {
        String query = x.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) return fallback;
        // No duplicates or unsupported parameters, even for read-only endpoints.
        if (!query.matches(key + "=[0-9]{1,6}"))
            throw new ApiError(400, "INVALID_QUERY", "expected only ?" + key + "=integer");
        try {
            int result = Integer.parseInt(query.substring(key.length() + 1));
            if (result < min || result > max) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException invalid) {
            throw new ApiError(400, "INVALID_QUERY", key + " must be between " + min + " and " + max);
        }
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> task)
            throws InterruptedException {
        try { return task.get(WAIT_SECONDS, TimeUnit.SECONDS); }
        catch (TimeoutException timedOut) {
            throw new ApiError(503, "READ_TIMEOUT", "sequencer read did not complete");
        } catch (ExecutionException failed) {
            throw new ApiError(503, "WRITER_FAILURE", "sequencer read could not complete");
        }
    }

    private void commonHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        exchange.getResponseHeaders().set("Vary", "Origin");
        if (config.allowedOrigin() != null && config.allowedOrigin().equals(
                exchange.getRequestHeaders().getFirst("Origin"))) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", config.allowedOrigin());
        }
    }

    private void send(HttpExchange exchange, int code, Object value) throws IOException {
        byte[] body = Json.encode(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        httpMetrics.response(code);
        exchange.getResponseBody().write(body);
    }

    private void sendError(HttpExchange exchange, int status, String code, String message,
                                  Long sequence) throws IOException {
        Map<String, Object> result = ApiView.of("error", ApiView.of("code", code, "message", message));
        if (sequence != null) result.put("sequence", sequence);
        send(exchange, status, result);
    }

    private static final class ApiError extends RuntimeException {
        private final int status;
        private final String code;
        private final Long sequence;
        ApiError(int status, String code, String message) { this(status, code, message, null); }
        ApiError(int status, String code, String message, Long sequence) {
            super(message);
            this.status = status;
            this.code = code;
            this.sequence = sequence;
        }
    }

    /** Global bounded token bucket; cap 240, refill 4/s. Not per-IP or production DDoS protection. */
    private static final class RateLimit {
        private static final double CAP = 240;
        private double tokens = CAP;
        private long last = System.nanoTime();
        synchronized boolean allow() {
            long now = System.nanoTime();
            tokens = Math.min(CAP, tokens + Math.max(0, now - last) / 1_000_000_000.0 * 4);
            last = now;
            if (tokens < 1) return false;
            tokens--;
            return true;
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        if (started) server.stop(1);
        sessionLock.writeLock().lock();
        try { engine.close(); }
        finally { sessionLock.writeLock().unlock(); executor.shutdown(); }
    }
}
