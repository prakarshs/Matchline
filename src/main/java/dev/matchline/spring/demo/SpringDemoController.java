package dev.matchline.spring.demo;

import dev.matchline.http.DemoSessions;
import dev.matchline.http.RecentTape;
import dev.matchline.observability.MetricsFormat;
import dev.matchline.sequencer.BookReadView;
import dev.matchline.sequencer.SingleWriterSequencer;
import dev.matchline.spring.MatchingRequestScope;
import dev.matchline.spring.api.SpringApiException;
import dev.matchline.spring.api.SpringApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser-facing read API and volatile sessions. One barrier read provides a
 * consistent book snapshot; tape entries represent HTTP-confirmed decisions
 * and are intentionally not a durable, replayed history.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class SpringDemoController {
    private final MatchingRequestScope scope;
    private final SpringRequestGuard guard;

    public SpringDemoController(MatchingRequestScope scope, SpringRequestGuard guard) {
        this.scope = scope;
        this.guard = guard;
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> newSession(HttpServletRequest request) throws Exception {
        if (!scope.publicDemo()) throw new SpringApiException(404, "NOT_FOUND", "unknown endpoint");
        String query = request.getQueryString();
        if (query != null && !"mode=preview".equals(query))
            throw new SpringApiException(400, "INVALID_QUERY", "expected no query or ?mode=preview");
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)
            throw new SpringApiException(400, "INVALID_BODY", "session creation accepts no body");
        DemoSessions.Issued issued = scope.sessions().create("mode=preview".equals(query));
        if (issued == null) {
            return ResponseEntity.status(429).header("Retry-After", "60")
                    .body(Map.of("error", Map.of("code", "SESSION_CAPACITY",
                            "message", "public demo is at session capacity; retry later")));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionToken", issued.session().token());
        response.put("idleTimeoutSeconds", DemoSessions.IDLE_SECONDS);
        response.put("mode", issued.session().preview() ? "preview" : "lab");
        response.put("durable", false);
        response.put("note", "Temporary isolated synthetic engine; session token is not a login");
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/demo/reset")
    public Map<String, Object> disabledReset() {
        throw new SpringApiException(404, "NOT_FOUND", "unknown endpoint");
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(HttpServletRequest request) {
        return scope.withEngine(request, context -> {
            int depth = numberQuery(request, "depth", 5, 1, 10);
            SingleWriterSequencer engine = context.writer();
            BookReadView book = read(engine.inspect(depth));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("book", SpringApiResponses.book(book));
            out.put("trades", context.tape().events(12, true));
            out.put("events", context.tape().events(12, false));
            out.put("status", status(context));
            out.put("metrics", metrics(context));
            out.put("recovery", recovery(context));
            return out;
        });
    }

    @GetMapping("/trades")
    public Map<String, Object> trades(HttpServletRequest request) {
        return scope.withEngine(request, context -> {
            int limit = numberQuery(request, "limit", 50, 1, 100);
            long sequence = read(context.writer().inspect(1)).lastProcessedSequence();
            return Map.of("instrument", context.writer().instrument().value(),
                    "observedThroughSequence", sequence, "source", "PROCESS_MEMORY",
                    "trades", context.tape().events(limit, true));
        });
    }

    @GetMapping("/events")
    public Map<String, Object> events(HttpServletRequest request) {
        return scope.withEngine(request, context -> {
            int limit = numberQuery(request, "limit", 50, 1, 100);
            long sequence = read(context.writer().inspect(1)).lastProcessedSequence();
            return Map.of("instrument", context.writer().instrument().value(),
                    "observedThroughSequence", sequence, "source", "PROCESS_MEMORY",
                    "events", context.tape().events(limit, false));
        });
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        return scope.withEngine(request, this::status);
    }

    @GetMapping("/recovery")
    public Map<String, Object> recovery(HttpServletRequest request) {
        return scope.withEngine(request, this::recovery);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(HttpServletRequest request) {
        return scope.withEngine(request, this::metrics);
    }

    private Map<String, Object> status(MatchingRequestScope.Engine context) {
        SingleWriterSequencer writer = context.writer();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("application", "MATCHLINE");
        out.put("instrument", writer.instrument().value());
        out.put("generation", 1);
        out.put("accepting", writer.accepting());
        out.put("healthy", writer.accepting() && writer.fatalFailure().isEmpty());
        out.put("lastAdmittedSequence", writer.lastAdmittedSequence());
        out.put("lastProcessedSequence", writer.lastProcessedSequence());
        out.put("queuedWork", writer.queuedWorkCount());
        out.put("queueCapacity", writer.queueCapacity());
        out.put("journalConfigured", false);
        out.put("durabilityPolicy", "NONE");
        out.put("tapeRecordedCommands", context.tape().size());
        out.put("tapeCapacity", RecentTape.MAX_COMMANDS);
        out.put("tradeTapeCapacity", RecentTape.MAX_TRADES);
        out.put("resetEnabled", false);
        out.put("publicDemo", context.publicDemo());
        out.put("sessionIsolated", context.publicDemo());
        return out;
    }

    private Map<String, Object> recovery(MatchingRequestScope.Engine context) {
        return Map.of("instrument", context.writer().instrument().value(),
                "journalConfigured", false,
                "durabilityPolicy", "NONE",
                "recoveredExistingJournal", false,
                "lastProcessedSequence", context.writer().lastProcessedSequence());
        // No journalFormatVersion or lastRecovery: optional fields omitted rather than fabricating values.
    }

    private Map<String, Object> metrics(MatchingRequestScope.Engine context) {
        SingleWriterSequencer writer = context.writer();
        return MetricsFormat.asJson(writer.metricsSnapshot(), guard.metrics().snapshot(), 1,
                writer.lastProcessedSequence(), writer.queuedWorkCount(), writer.queueCapacity());
    }

    private static int numberQuery(HttpServletRequest request, String name, int fallback, int min, int max) {
        String raw = request.getQueryString();
        if (raw == null || raw.isEmpty()) return fallback;
        if (!raw.matches(name + "=[0-9]{1,6}"))
            throw new SpringApiException(400, "INVALID_QUERY", "expected only ?" + name + "=integer");
        int n;
        try { n = Integer.parseInt(raw.substring(name.length() + 1)); }
        catch (NumberFormatException invalid) { n = -1; }
        if (n < min || n > max)
            throw new SpringApiException(400, "INVALID_QUERY", name + " must be between " + min + " and " + max);
        return n;
    }

    private static <T> T read(java.util.concurrent.CompletableFuture<T> pending) {
        try { return pending.get(5, TimeUnit.SECONDS); }
        catch (TimeoutException timedOut) {
            throw new SpringApiException(503, "READ_TIMEOUT", "sequencer read did not complete");
        } catch (ExecutionException failed) {
            throw new SpringApiException(503, "WRITER_FAILURE", "sequencer read could not complete");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SpringApiException(503, "INTERRUPTED", "request interrupted");
        }
    }
}
