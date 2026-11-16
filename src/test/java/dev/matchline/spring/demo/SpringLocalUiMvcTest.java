package dev.matchline.spring.demo;

import dev.matchline.spring.MatchlineSpringApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = MatchlineSpringApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SpringLocalUiMvcTest {
    @Autowired MockMvc mvc;

    @Test void localSpringLabWorksWithoutSessionAndDoesNotSilentlyClaimDurability() throws Exception {
        mvc.perform(get("/lab")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/dashboard?depth=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book.asOfSequence").value(0))
                .andExpect(jsonPath("$.recovery.journalConfigured").value(false))
                .andExpect(jsonPath("$.status.sessionIsolated").value(false));
        mvc.perform(post("/api/v1/session"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/preview"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
    }
}
