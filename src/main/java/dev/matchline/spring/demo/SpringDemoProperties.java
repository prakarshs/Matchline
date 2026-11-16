package dev.matchline.spring.demo;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Safe public-demo configuration. No journal or global reset in visitor mode. */
@ConfigurationProperties(prefix = "matchline.demo")
public final class SpringDemoProperties {
    private boolean enabled;
    private String embedOrigin = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEmbedOrigin() { return embedOrigin; }
    public void setEmbedOrigin(String embedOrigin) { this.embedOrigin = embedOrigin; }

    public void validate() {
        if (embedOrigin == null || embedOrigin.isBlank()) {
            embedOrigin = "";
            return;
        }
        // A CSP frame ancestor must be ONE explicit origin: never accept paths,
        // quotes, wildcards, a URI fragment, or characters that can inject policy.
        if (!embedOrigin.matches("https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?")) {
            throw new IllegalArgumentException("matchline.demo.embed-origin must be an explicit http(s) origin without a path");
        }
        URI parsed = URI.create(embedOrigin);
        if (parsed.getHost() == null || parsed.getHost().isBlank() || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("matchline.demo.embed-origin must have a valid hostname");
        }
    }
}
