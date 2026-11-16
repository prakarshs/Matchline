package dev.matchline.spring.api;

import dev.matchline.book.LimitOrderBook;
import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderValidationException;
import dev.matchline.sequencer.CommandDecision;
import dev.matchline.sequencer.CommandTicket;
import dev.matchline.sequencer.SequencedResult;
import dev.matchline.spring.MatchingRequestScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin Spring MVC transport. Construct domain commands, call the S02 facade,
 * then serialize immutable DTOs. Never mutate LimitOrderBook directly.
 *
 * <p>S04 selects either the local Spring writer or a leased, ephemeral
 * visitor-owned writer. The journal-backed public deployment remains on the
 * old adapter until S05. An admitted command may have an unknown outcome
 * on timeout: the caller must inspect state, not blindly retry a new ID.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class SpringOrdersController {
    private static final int WAIT_SECONDS = 5;
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,6}");
    private final MatchingRequestScope scope;

    public SpringOrdersController(MatchingRequestScope scope) {
        this.scope = scope;
    }

    @PostMapping(path = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SpringApiResponses.Decision> place(@RequestBody Map<String, Object> payload,
                                                              HttpServletRequest request) {
        return scope.withEngine(request, context -> complete(PlaceOrderRequest.fromJson(payload).toCommand(), context));
    }

    @PostMapping(path = "/orders/{id}/cancel")
    public ResponseEntity<SpringApiResponses.Decision> cancel(@PathVariable String id,
                                                               HttpServletRequest request) {
        return scope.withEngine(request,
                context -> complete(new CancelOrder(context.writer().instrument(), parsePathId(id)), context));
    }

    @GetMapping("/orders/{id}")
    public SpringApiResponses.Lookup find(@PathVariable String id, HttpServletRequest request) {
        return scope.withEngine(request, context -> {
            Optional<dev.matchline.book.RestingOrderView> found = waitRead(context.writer().find(parsePathId(id)));
            if (found.isEmpty()) {
                throw new SpringApiException(404, "ORDER_NOT_FOUND", "no resting order with this ID");
            }
            return new SpringApiResponses.Lookup(context.writer().instrument().value(),
                    SpringApiResponses.order(found.get()));
        });
    }

    @GetMapping("/book")
    public SpringApiResponses.Book book(@RequestParam MultiValueMap<String, String> query,
                                        HttpServletRequest request) {
        return scope.withEngine(request, context ->
                SpringApiResponses.book(waitRead(context.writer().inspect(parseDepth(query)))));
    }

    private ResponseEntity<SpringApiResponses.Decision> complete(dev.matchline.domain.OrderCommand command,
                                                                     MatchingRequestScope.Engine context) {
        CommandTicket ticket = context.writer().submit(command);
        SequencedResult outcome;
        try {
            outcome = ticket.completion().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new SpringApiException(504, "OUTCOME_UNKNOWN",
                    "command was admitted; outcome not confirmed; inspect status before retrying",
                    ticket.sequence());
        } catch (ExecutionException failure) {
            throw new SpringApiException(503, "WRITER_FAILURE",
                    "writer stopped before acknowledgement; outcome may be unknown", ticket.sequence());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SpringApiException(503, "INTERRUPTED", "request interrupted", ticket.sequence());
        }
        // Record only confirmed outcomes; timeouts must not invent completed events.
        context.tape().record(outcome);
        int status = outcome.decision() instanceof CommandDecision.Rejected ? 409 : 200;
        return ResponseEntity.status(status).body(SpringApiResponses.decision(outcome));
    }

    private static <T> T waitRead(CompletableFuture<T> pending) {
        try {
            return pending.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new SpringApiException(503, "READ_TIMEOUT", "sequencer read did not complete");
        } catch (ExecutionException failed) {
            throw new SpringApiException(503, "WRITER_FAILURE", "sequencer read could not complete");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SpringApiException(503, "INTERRUPTED", "request interrupted");
        }
    }

    private static OrderId parsePathId(String id) {
        if (id.isEmpty() || id.indexOf('/') >= 0 || id.indexOf('%') >= 0) {
            throw new SpringApiException(404, "NOT_FOUND", "unknown endpoint");
        }
        return new OrderId(id);
    }

    private static int parseDepth(MultiValueMap<String, String> query) {
        if (query.isEmpty()) return 10;
        if (query.size() != 1 || !query.containsKey("depth")
                || query.get("depth") == null || query.get("depth").size() != 1) {
            throw new SpringApiException(400, "INVALID_QUERY", "expected only ?depth=integer");
        }
        String value = query.getFirst("depth");
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw new SpringApiException(400, "INVALID_QUERY", "expected only ?depth=integer");
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > LimitOrderBook.MAX_DEPTH_LEVELS) {
            throw new SpringApiException(400, "INVALID_QUERY", "depth must be between 1 and " + LimitOrderBook.MAX_DEPTH_LEVELS);
        }
        return parsed;
    }
}
