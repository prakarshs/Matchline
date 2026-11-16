package dev.matchline.spring.demo;

import dev.matchline.http.DemoSessions;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise genuinely unknown Content-Length independently of MockMvc's inferred length. */
final class BoundedRequestBodyTest {
    private static MockHttpServletRequest chunked(int size) {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        request.setMethod("POST");
        request.setRequestURI("/api/v1/orders");
        request.addHeader("Transfer-Encoding", "chunked");
        request.setContent("x".repeat(size).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test void unknownLengthOversizeRequestIsRefusedBeforeController() throws Exception {
        try (DemoSessions sessions = new DemoSessions()) {
            SpringRequestGuard guard = new SpringRequestGuard(new SpringDemoProperties(), sessions);
            MockHttpServletResponse response = new MockHttpServletResponse();
            guard.doFilter(chunked(4097), response,
                    (req,res) -> fail("Oversized body must never reach the controller"));
            assertEquals(413, response.getStatus());
            assertTrue(response.getContentAsString().contains("BODY_TOO_LARGE"));
            assertEquals(0, guard.activeRequests());
        }
    }

    @Test void acceptedChunkedBodyIsBoundedAndReadableExactlyOnceDownstream() throws Exception {
        try (DemoSessions sessions = new DemoSessions()) {
            SpringRequestGuard guard = new SpringRequestGuard(new SpringDemoProperties(), sessions);
            MockHttpServletResponse response = new MockHttpServletResponse();
            guard.doFilter(chunked(4096), response, (req,res) -> {
                HttpServletRequest bounded = (HttpServletRequest) req;
                assertEquals(4096, bounded.getContentLengthLong());
                assertEquals(4096, bounded.getInputStream().readAllBytes().length);
            });
            assertEquals(200, response.getStatus());
            assertEquals(0, guard.activeRequests());
        }
    }
}
