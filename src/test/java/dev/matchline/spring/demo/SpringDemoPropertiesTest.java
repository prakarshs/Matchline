package dev.matchline.spring.demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpringDemoPropertiesTest {
    @Test void onlyExplicitHttpOriginsAreAcceptedForFrameAncestors() {
        for (String invalid : new String[]{"*", "https://example.org/a", "https://example.org;default-src *",
                "javascript:alert(1)", "https://example.org/", "https://example.org?a=1"}) {
            SpringDemoProperties p = new SpringDemoProperties();
            p.setEmbedOrigin(invalid);
            assertThrows(IllegalArgumentException.class, p::validate, invalid);
        }
        SpringDemoProperties okay = new SpringDemoProperties();
        okay.setEmbedOrigin("https://portfolio.jhethia.workers.dev");
        assertDoesNotThrow(okay::validate);
    }
}
