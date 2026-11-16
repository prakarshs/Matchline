package dev.matchline.spring.demo;

import dev.matchline.observability.HttpMetrics;
import dev.matchline.http.DemoSessions;
import dev.matchline.spring.MatchingRequestScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Coarse process-wide admission and security headers for public demo. This is
 * NOT per-user abuse protection or a substitute for reverse-proxy limits.
 * Session tokens are never logged or used as metric labels.
 */
@Component
public final class SpringRequestGuard extends OncePerRequestFilter {
    private static final int MAX_IN_FLIGHT = 64;
    private static final double BURST = 240;
    private final Semaphore active = new Semaphore(MAX_IN_FLIGHT);
    private final HttpMetrics metrics = new HttpMetrics();
    private final SpringDemoProperties properties;
    private final DemoSessions sessions;

    public SpringRequestGuard(SpringDemoProperties properties, DemoSessions sessions) {
        this.properties = properties;
        this.sessions = sessions;
    }
    private double tokens = BURST;
    private long lastRefill = System.nanoTime();

    public HttpMetrics metrics() { return metrics; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Vary", "Origin");
        boolean entered = false;
        DemoSessions.Session leased = null;
        try {
            if (!active.tryAcquire()) {
                reject(response, 503, "SERVER_BUSY", "too many simultaneous requests");
                return;
            }
            entered = true;
            String path = request.getRequestURI();
            String method = request.getMethod();
            if (!"GET".equals(method) && !"POST".equals(method)) {
                response.setHeader("Allow", "GET, POST");
                reject(response, 405, "METHOD_NOT_ALLOWED", "only GET and POST are supported");
                return;
            }
            String origin = request.getHeader("Origin");
            if (origin != null && !sameHostOrigin(origin, request.getHeader("Host"))) {
                reject(response, 403, "ORIGIN_DENIED", "origin not permitted");
                return;
            }
            // The process-wide Actuator metrics exporter includes runtime details;
            // do not expose it in the unauthenticated public-demo configuration.
            if (properties.isEnabled() && path.startsWith("/actuator") && !"/actuator/health".equals(path)) {
                reject(response, 404, "NOT_FOUND", "unknown endpoint");
                return;
            }
            if (!"/api/v1/health".equals(path) && !"/actuator/health".equals(path) && !allow()) {
                response.setHeader("Retry-After", "1");
                reject(response, 429, "HTTP_RATE_LIMIT", "global HTTP request budget exhausted");
                return;
            }
            // Basic bound on normal Content-Length bodies. Reverse-proxy limits
            // are still required to cap chunked uploads before S05 cutover.
            if (request.getContentLengthLong() > 4096 && "/api/v1/orders".equals(path)) {
                reject(response, 413, "BODY_TOO_LARGE", "request body exceeds 4096 bytes");
                return;
            }
            // Fail unauthenticated requests before MVC/Jackson deserializes a body.
            // A lease protects the writer for the ENTIRE HTTP request, not only
            // the matching controller's execution window.
            if (properties.isEnabled() && path.startsWith("/api/v1/")
                    && !"/api/v1/health".equals(path) && !"/api/v1/session".equals(path)
                    && !"/api/v1/demo/reset".equals(path)) {
                String token = request.getHeader("X-Matchline-Session");
                if (token == null || token.isBlank()) {
                    reject(response, 401, "SESSION_REQUIRED", "create a public demo session first");
                    return;
                }
                leased = sessions.acquire(token);
                if (leased == null) {
                    reject(response, 401, "SESSION_EXPIRED", "demo session expired; start a fresh session");
                    return;
                }
                request.setAttribute(MatchingRequestScope.LEASED_SESSION_ATTRIBUTE, leased);
            }
            chain.doFilter(request, response);
        } finally {
            if (leased != null) leased.release();
            if (entered) active.release();
            metrics.response(response.getStatus());
            metrics.duration(System.nanoTime() - started);
        }
    }

    private static boolean sameHostOrigin(String origin, String hostHeader) {
        if (hostHeader == null || origin.indexOf(' ') >= 0 || origin.indexOf(';') >= 0) return false;
        try {
            URI parsed = URI.create(origin);
            if (!"http".equals(parsed.getScheme()) && !"https".equals(parsed.getScheme())) return false;
            if (parsed.getRawPath() != null && !parsed.getRawPath().isEmpty()) return false;
            if (parsed.getRawQuery() != null || parsed.getRawFragment() != null || parsed.getUserInfo() != null) return false;
            String authority = parsed.getHost() + (parsed.getPort() == -1 ? "" : ":" + parsed.getPort());
            return hostHeader.equalsIgnoreCase(authority)
                    || (parsed.getPort() == -1 && hostHeader.equalsIgnoreCase(parsed.getHost() +
                        ("https".equals(parsed.getScheme()) ? ":443" : ":80")));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private synchronized boolean allow() {
        long now = System.nanoTime();
        tokens = Math.min(BURST, tokens + Math.max(0, now - lastRefill) / 1_000_000_000.0 * 4);
        lastRefill = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }

    private static void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
    }
}
