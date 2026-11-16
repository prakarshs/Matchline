package dev.matchline.spring;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Checks that custom meters report ACTUAL local sequencer state. */
@SpringBootTest(classes = MatchlineSpringApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SpringObservabilityMvcTest {
    @Autowired MockMvc mvc;
    @Autowired MeterRegistry registry;

    @Test void customMicrometerMetersReflectRealCommandsInLocalMode() throws Exception {
        assertEquals(0, registry.get("matchline.demo.enabled").gauge().value());
        assertEquals(0, registry.get("matchline.demo.active.sessions").gauge().value());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrument\":\"DEMO\",\"orderId\":\"M1\",\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequence").value(1));
        assertEquals(1, registry.get("matchline.local.writer.last.sequence").gauge().value());
        assertEquals(1, registry.get("matchline.local.writer.commands.completed").functionCounter().count());
        assertTrue(registry.get("matchline.local.writer.queue.capacity").gauge().value() > 0);
    }

    @Test void localActuatorExportsMicrometerTextAndMonitorIsUnframeable() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("matchline_demo_active_sessions")));
        mvc.perform(get("/monitor"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Session Telemetry")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
        mvc.perform(get("/ui/monitor.mjs"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/v1/metrics")));
    }
}
