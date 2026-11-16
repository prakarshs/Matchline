package dev.matchline.spring;

import dev.matchline.http.DemoSessions;
import dev.matchline.http.RecentTape;
import dev.matchline.sequencer.SingleWriterSequencer;
import dev.matchline.spring.api.SpringApiException;
import dev.matchline.spring.demo.SpringDemoProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Routes each HTTP request to exactly one writer. In public-demo mode, the
 * session manager holds a lease until the entire request has completed, so
 * idle expiry cannot close the writer while a command is still in flight.
 * Local mode retains S02's one singleton writer and a bounded volatile tape.
 */
@Service
public final class MatchingRequestScope {
    public record Engine(SingleWriterSequencer writer, RecentTape tape,
                         boolean publicDemo, boolean preview) { }

    /** The servlet filter pins a public visitor's session for the entire request. */
    public static final String LEASED_SESSION_ATTRIBUTE = MatchingRequestScope.class.getName() + ".leasedSession";

    private final MatchingApplicationService local;
    private final SpringDemoProperties properties;
    private final DemoSessions sessions;
    private final RecentTape localTape = new RecentTape(false);

    public MatchingRequestScope(MatchingApplicationService local, SpringDemoProperties properties,
                                DemoSessions sessions) {
        this.local = Objects.requireNonNull(local);
        this.properties = Objects.requireNonNull(properties);
        this.sessions = Objects.requireNonNull(sessions);
    }

    public boolean publicDemo() { return properties.isEnabled(); }
    public DemoSessions sessions() { return sessions; }

    public <T> T withEngine(HttpServletRequest request, Function<Engine, T> work) {
        if (!properties.isEnabled()) {
            return work.apply(new Engine(local.sequencer(), localTape, false, false));
        }
        String token = request.getHeader("X-Matchline-Session");
        if (token == null || token.isBlank()) {
            throw new SpringApiException(401, "SESSION_REQUIRED", "create a public demo session first");
        }
        // Normally the filter has already acquired a lease before JSON binding.
        // Fallback covers direct service use (including unit tests).
        DemoSessions.Session alreadyLeased = (DemoSessions.Session)
                request.getAttribute(LEASED_SESSION_ATTRIBUTE);
        if (alreadyLeased != null) {
            return work.apply(new Engine(alreadyLeased.engine(), alreadyLeased.tape(), true,
                    alreadyLeased.preview()));
        }
        DemoSessions.Session session = sessions.acquire(token);
        if (session == null) {
            throw new SpringApiException(401, "SESSION_EXPIRED", "demo session expired; start a fresh session");
        }
        try {
            return work.apply(new Engine(session.engine(), session.tape(), true, session.preview()));
        } finally {
            session.release();
        }
    }
}
