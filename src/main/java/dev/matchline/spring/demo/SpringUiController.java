package dev.matchline.spring.demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Strict allowlist of packaged UI resources; no dynamic filesystem lookup. */
@Controller
public final class SpringUiController {
    private record Asset(String classpath, String contentType, boolean frameCandidate) { }
    private static final Map<String, Asset> ASSETS = Map.of(
            "/lab", new Asset("/ui/lab.html", "text/html", false),
            "/embed", new Asset("/ui/lab.html", "text/html", true),
            "/preview", new Asset("/ui/preview.html", "text/html", true),
            "/monitor", new Asset("/ui/monitor.html", "text/html", false),
            "/ui/app.css", new Asset("/ui/app.css", "text/css", false),
            "/ui/monitor.css", new Asset("/ui/monitor.css", "text/css", false),
            "/ui/monitor.mjs", new Asset("/ui/monitor.mjs", "text/javascript", false),
            "/ui/app.mjs", new Asset("/ui/app.mjs", "text/javascript", false),
            "/ui/mark.svg", new Asset("/ui/mark.svg", "image/svg+xml", false),
            "/openapi.yaml", new Asset("/openapi.yaml", "application/yaml", false)
    );
    private final SpringDemoProperties properties;

    public SpringUiController(SpringDemoProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/")
    public ResponseEntity<Void> home() {
        return ResponseEntity.status(302).location(java.net.URI.create("/lab"))
                .cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping({"/lab", "/lab/", "/embed", "/embed/", "/preview", "/preview/",
            "/ui/app.css", "/ui/app.mjs", "/ui/mark.svg", "/ui/monitor.css",
            "/ui/monitor.mjs", "/monitor", "/monitor/", "/openapi.yaml"})
    public ResponseEntity<byte[]> asset(jakarta.servlet.http.HttpServletRequest request) throws IOException {
        String path = request.getRequestURI();
        String normalized = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        Asset asset = ASSETS.get(normalized);
        if (asset == null) return ResponseEntity.notFound().build();
        String ancestors = "'none'";
        if (asset.frameCandidate() && !properties.getEmbedOrigin().isBlank()
                && (!normalized.equals("/embed") || properties.isEnabled())) {
            ancestors = properties.getEmbedOrigin();
        }
        String csp = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                + "img-src 'self'; font-src 'self'; base-uri 'none'; form-action 'none'; "
                + "object-src 'none'; frame-ancestors " + ancestors;
        byte[] bytes;
        try (InputStream stream = SpringUiController.class.getResourceAsStream(asset.classpath())) {
            if (stream == null) throw new IOException("missing packaged UI asset: " + asset.classpath());
            bytes = stream.readAllBytes();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", csp)
                .header("Referrer-Policy", "no-referrer")
                .header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
                .contentType(MediaType.parseMediaType(asset.contentType() + ";charset=utf-8"))
                .body(bytes);
    }
}
