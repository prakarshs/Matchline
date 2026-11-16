package dev.matchline.spring.demo;

import dev.matchline.http.DemoSessions;
import dev.matchline.spring.MatchingRequestScope;
import dev.matchline.spring.MatchlineSpringApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Run actual Spring MVC+Jackson+filter+shared per-visitor Java writer. */
@SpringBootTest(classes = MatchlineSpringApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {"matchline.demo.enabled=true", "matchline.demo.embed-origin=https://portfolio.example.org"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SpringPublicDemoMvcTest {
    @Autowired MockMvc mvc;
    @Autowired DemoSessions sessions;
    @Autowired MatchingRequestScope scope;

    private static final String ORDER = "{\"instrument\":\"DEMO\",\"orderId\":\"B1\",\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":4}";

    private String issue(boolean preview) throws Exception {
        String body = mvc.perform(post("/api/v1/session" + (preview ? "?mode=preview" : "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durable").value(false))
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"sessionToken\":\"([A-Za-z0-9_-]{43})\"").matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    @Test void missingOrInvalidSessionIsRejectedBeforeMatching() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SESSION_REQUIRED"));
        // A missing capability is refused before Jackson can try to parse the body.
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{not-json}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SESSION_REQUIRED"));
        String token = issue(false);
        mvc.perform(post("/api/v1/orders").header("X-Matchline-Session", "invalid")
                .contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        mvc.perform(post("/api/v1/orders").header("X-Matchline-Session", token)
                .contentType(MediaType.APPLICATION_JSON).content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_JSON"));
        mvc.perform(get("/api/v1/dashboard?depth=5").header("X-Matchline-Session", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.book.asOfSequence").value(0));
    }

    @Test void twoBrowserSessionsAreIndependentAndTheirOrderIdsDoNotConflict() throws Exception {
        String a = issue(false), b = issue(false);
        assertNotEquals(a, b);
        mvc.perform(post("/api/v1/orders").header("X-Matchline-Session", a)
                .contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequence").value(1));
        mvc.perform(get("/api/v1/dashboard?depth=5").header("X-Matchline-Session", a))
                .andExpect(jsonPath("$.book.asOfSequence").value(1))
                .andExpect(jsonPath("$.events.length()").value(1));
        mvc.perform(get("/api/v1/dashboard?depth=5").header("X-Matchline-Session", b))
                .andExpect(jsonPath("$.book.asOfSequence").value(0))
                .andExpect(jsonPath("$.events").isEmpty());
        mvc.perform(get("/api/v1/orders/B1").header("X-Matchline-Session", b))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders").header("X-Matchline-Session", b)
                .contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequence").value(1));
    }

    @Test void previewIsSeededByTheActualSequencerWithRealTrade() throws Exception {
        String token = issue(true);
        mvc.perform(get("/api/v1/dashboard?depth=5").header("X-Matchline-Session", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book.asOfSequence").value(5))
                .andExpect(jsonPath("$.trades[0].makerOrderId").value("SHOW-S1"))
                .andExpect(jsonPath("$.trades[0].takerOrderId").value("SHOW-T1"));
    }

    @Test void fillsAndEventTapeRemainPrivateToTheSameSession() throws Exception {
        String a = issue(false), b = issue(false);
        mvc.perform(post("/api/v1/orders").header("X-Matchline-Session", a)
                .contentType(MediaType.APPLICATION_JSON).content(ORDER)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders").header("X-Matchline-Session", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrument\":\"DEMO\",\"orderId\":\"S1\",\"side\":\"SELL\",\"priceTicks\":99,\"quantity\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trades[0].quantity").value(3));
        mvc.perform(get("/api/v1/trades?limit=10").header("X-Matchline-Session", a))
                .andExpect(jsonPath("$.trades[0].priceTicks").value(100));
        mvc.perform(get("/api/v1/events?limit=10").header("X-Matchline-Session", b))
                .andExpect(jsonPath("$.events").isEmpty());
        mvc.perform(get("/api/v1/metrics").header("X-Matchline-Session", a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.writer.completed").value(2));
        mvc.perform(get("/api/v1/recovery").header("X-Matchline-Session", a))
                .andExpect(jsonPath("$.journalConfigured").value(false));
    }

    @Test void sessionCapacityRejectsNewVisitorsWithoutDiscardingOldOnes() throws Exception {
        String first = null;
        for (int i=0; i<DemoSessions.MAX_SESSIONS; i++) {
            String token = issue(false);
            if (first == null) first = token;
        }
        mvc.perform(post("/api/v1/session"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error.code").value("SESSION_CAPACITY"));
        mvc.perform(get("/api/v1/dashboard").header("X-Matchline-Session", first))
                .andExpect(status().isOk());
    }

    @Test void uiRoutesHaveCorrectFramePolicyAndCannotServeArbitraryFiles() throws Exception {
        mvc.perform(get("/preview"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors https://portfolio.example.org")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-view=\"preview\"")));
        mvc.perform(get("/embed"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors https://portfolio.example.org")));
        mvc.perform(get("/lab"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
        mvc.perform(get("/ui/app.mjs"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/v1/dashboard")));
        mvc.perform(get("/ui/.env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
        mvc.perform(get("/"))
                .andExpect(status().isFound()).andExpect(header().string("Location", "/lab"));
    }

    @Test void malformedQueriesAndGlobalResetAreRejected() throws Exception {
        String token = issue(false);
        mvc.perform(get("/api/v1/dashboard?depth=0").header("X-Matchline-Session", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/v1/events?limit=1&limit=2").header("X-Matchline-Session", token))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/demo/reset").header("X-Matchline-Session", token))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/session?mode=admin"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").header("X-Matchline-Session", token)
                .contentType(MediaType.APPLICATION_JSON).content("x".repeat(4097)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("BODY_TOO_LARGE"));
        mvc.perform(post("/api/v1/session").header("Content-Type", "text/plain").content("x"))
                .andExpect(status().isBadRequest());
    }

    @Test void sameOriginPolicyRefusesCrossOriginCommandEvenWithSessionToken() throws Exception {
        String token = issue(false);
        mvc.perform(post("/api/v1/orders").header("Host", "localhost:8082")
                .header("Origin", "https://evil.example")
                .header("X-Matchline-Session", token)
                .contentType(MediaType.APPLICATION_JSON).content(ORDER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ORIGIN_DENIED"));
        mvc.perform(get("/api/v1/dashboard").header("X-Matchline-Session", token))
                .andExpect(jsonPath("$.book.asOfSequence").value(0));
    }

    @Test void aRetiredSessionKeepsItsWriterUntilSpringRequestScopeReleasesLease() throws Exception {
        DemoSessions.Session session = sessions.create(false).session();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Matchline-Session", session.token());
        scope.withEngine(request, engine -> {
            sessions.close();
            assertTrue(engine.writer().accepting());
            return null;
        });
        assertFalse(session.engine().accepting());
    }
}
