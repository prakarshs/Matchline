package dev.matchline.http;

import dev.matchline.book.LimitOrderBook;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;
import dev.matchline.sequencer.SequencedResult;
import dev.matchline.sequencer.SingleWriterSequencer;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Bounded, ephemeral public-demo sessions. A session token is an unguessable
 * capability to a *synthetic* in-memory book, not a user login or durable ID.
 * No cookies are required: portfolio iframes can use X-Matchline-Session.
 */
final class DemoSessions implements AutoCloseable {
    static final int MAX_SESSIONS = 12;
    static final int MAX_RESTING_PER_SESSION = 500;
    static final int MAX_IDS_PER_SESSION = 3_000;
    static final int QUEUE_CAPACITY = 64;
    static final long IDLE_SECONDS = 20 * 60;
    private static final long IDLE_NANOS = Duration.ofSeconds(IDLE_SECONDS).toNanos();
    private static final InstrumentId DEMO = new InstrumentId("DEMO");
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private final Map<String, Session> sessions = new HashMap<>();
    private boolean closed;

    DemoSessions() { this(System::nanoTime); }
    DemoSessions(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    static final class Session implements AutoCloseable {
        final String token;
        final SingleWriterSequencer engine;
        final RecentTape tape = new RecentTape(false);
        final boolean preview;
        volatile long lastSeen;
        private int inFlight;
        private boolean retired;
        private boolean stopped;

        Session(String token, long now, boolean preview) {
            this.token = token;
            this.lastSeen = now;
            this.preview = preview;
            this.engine = new SingleWriterSequencer(DEMO, QUEUE_CAPACITY,
                    MAX_RESTING_PER_SESSION, MAX_IDS_PER_SESSION);
        }

        /** Callers hold a lease while an HTTP operation is in progress. */
        synchronized boolean acquire(long now) {
            if (retired) return false;
            lastSeen = now;
            inFlight++;
            return true;
        }

        synchronized void release() {
            if (inFlight < 1) throw new IllegalStateException("unbalanced lease");
            inFlight--;
            if (inFlight == 0 && retired) stop();
        }

        synchronized void retire() {
            retired = true;
            if (inFlight == 0) stop();
        }

        private void stop() {
            if (!stopped) {
                stopped = true;
                engine.close();
            }
        }

        @Override public void close() { retire(); }
    }

    record Issued(Session session) { }

    /** Returns null when all capacity is occupied by non-idle sessions. */
    synchronized Issued create(boolean preview) throws Exception {
        if (closed) throw new IllegalStateException("sessions already closed");
        long now = clock.getAsLong();
        expire(now);
        if (sessions.size() >= MAX_SESSIONS) return null;
        byte[] bytes = new byte[32];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (sessions.containsKey(token));
        Session session = new Session(token, now, preview);
        try {
            if (preview) seedPreview(session);
            sessions.put(token, session);
            return new Issued(session);
        } catch (Exception failure) {
            session.close();
            throw failure;
        }
    }

    /** Null means invalid, unknown, expired, or retired; do not leak which. */
    synchronized Session acquire(String token) {
        long now = clock.getAsLong();
        expire(now);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        Session session = sessions.get(token);
        return session != null && session.acquire(now) ? session : null;
    }

    synchronized int count() { expire(clock.getAsLong()); return sessions.size(); }

    private void expire(long now) {
        var expired = new ArrayList<Session>();
        sessions.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            if (now - session.lastSeen >= IDLE_NANOS) {
                expired.add(session);
                return true;
            }
            return false;
        });
        // Retire outside map mutation; active requests keep their leases.
        expired.forEach(Session::retire);
    }

    private static void seedPreview(Session session) throws Exception {
        // Actual sequenced Java matching, never hardcoded trade JSON.
        place(session, "SHOW-B1", Side.BUY, 99, 6);
        place(session, "SHOW-B2", Side.BUY, 98, 4);
        place(session, "SHOW-S1", Side.SELL, 102, 5);
        place(session, "SHOW-S2", Side.SELL, 103, 3);
        place(session, "SHOW-T1", Side.BUY, 102, 2);
    }

    private static void place(Session session, String id, Side side, long price, long quantity) throws Exception {
        var ticket = session.engine.submit(new PlaceLimitOrder(DEMO, new OrderId(id),
                side, new PriceTicks(price), new OrderQuantity(quantity)));
        SequencedResult result = ticket.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        session.tape.record(result);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        sessions.values().forEach(Session::retire);
        sessions.clear();
    }
}
