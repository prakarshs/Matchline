package dev.matchline.spring.demo;

import dev.matchline.spring.MatchlineSpringApplication;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public cutover checks: private scrape endpoint, isolated session metrics, bounded uploads. */
@SpringBootTest(classes = MatchlineSpringApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {"matchline.demo.enabled=true", "matchline.demo.embed-origin=https://portfolio.example.org"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SpringReleaseMvcTest {
    @Autowired MockMvc mvc;
    @Autowired MeterRegistry registry;

    private String newSession() throws Exception {
        String json = mvc.perform(post("/api/v1/session")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = Pattern.compile("\"sessionToken\":\"([A-Za-z0-9_-]{43})\"").matcher(json);
        assertTrue(matcher.find(), json);
        return matcher.group(1);
    }

    @Test void publicExporterRemainsPrivateButBoundedSessionMetricsWork() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
        mvc.perform(get("/monitor")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
        String token = newSession();
        mvc.perform(get("/api/v1/metrics").header("X-Matchline-Session", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.writer.completed").value(0));
        mvc.perform(post("/api/v1/orders").header("X-Matchline-Session", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrument\":\"DEMO\",\"orderId\":\"T1\",\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":1}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/metrics").header("X-Matchline-Session", token))
                .andExpect(jsonPath("$.writer.completed").value(1));
        assertEquals(1, registry.get("matchline.demo.active.sessions").gauge().value());
        // This is the intentionally dormant singleton, not the visitor's book.
        assertEquals(0, registry.get("matchline.local.writer.last.sequence").gauge().value());
    }

    @Test void oversizedUnknownLengthTransferIsRejectedBeforeJacksonOrTheWriter() throws Exception {
        String token = newSession();
        mvc.perform(post("/api/v1/orders").header("X-Matchline-Session", token)
                .header("Transfer-Encoding", "chunked")
                .contentType(MediaType.APPLICATION_JSON).content("x".repeat(4097)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("BODY_TOO_LARGE"));
        mvc.perform(get("/api/v1/metrics").header("X-Matchline-Session", token))
                .andExpect(jsonPath("$.writer.admitted").value(0));
    }
}
