package dev.matchline.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DemoSessionsTest {
    @Test void distinctSessionsOwnDistinctBooks() throws Exception {
        try (var sessions = new DemoSessions()) {
            var a = sessions.create(false).session();
            var b = sessions.create(false).session();
            assertNotEquals(a.token,b.token);
            assertNotSame(a.engine,b.engine);
            assertEquals(0,a.engine.lastProcessedSequence());
            assertEquals(0,b.engine.lastProcessedSequence());
            assertTrue(sessions.acquire(a.token)!=null);
            a.release();
        }
    }

    @Test void previewIsSeededThroughRealWriterAndTape() throws Exception {
        try (var sessions = new DemoSessions()) {
            var preview = sessions.create(true).session();
            assertTrue(preview.preview);
            assertEquals(5,preview.engine.lastProcessedSequence());
            assertEquals(1,preview.tape.events(10,true).size());
            var lab = sessions.create(false).session();
            assertEquals(0,lab.engine.lastProcessedSequence());
            assertTrue(lab.tape.events(10,true).isEmpty());
        }
    }

    @Test void capacityIsBoundedAndCreatesNoExtraWriter() throws Exception {
        try (var sessions = new DemoSessions()) {
            var tokens=new ArrayList<String>();
            for (int i=0;i<DemoSessions.MAX_SESSIONS;i++) {
                var issued=sessions.create(false);
                assertNotNull(issued);
                tokens.add(issued.session().token);
            }
            assertEquals(DemoSessions.MAX_SESSIONS,sessions.count());
            assertNull(sessions.create(false));
            for (String token:tokens) {
                var lease = sessions.acquire(token);
                assertNotNull(lease);
                lease.release();
            }
        }
    }

    @Test void idleExpiryAndActiveLeaseAreSafe() throws Exception {
        AtomicLong time = new AtomicLong(0);
        try (var sessions = new DemoSessions(time::get)) {
            var s = sessions.create(false).session();
            assertNotNull(sessions.acquire(s.token));
            time.addAndGet(java.time.Duration.ofMinutes(21).toNanos());
            assertNull(sessions.acquire(s.token));
            assertEquals(0,sessions.count());
            assertTrue(s.engine.accepting()); // existing HTTP request keeps its lease
            s.release();
            assertFalse(s.engine.accepting());
            assertNotNull(sessions.create(false));
        }
    }

    @Test void malformedOrUnknownTokenCannotAccessAnotherBook() throws Exception {
        try (var sessions = new DemoSessions()) {
            sessions.create(false);
            assertNull(sessions.acquire(null));
            assertNull(sessions.acquire("not-a-token"));
            assertNull(sessions.acquire("a".repeat(43)));
        }
    }
}
