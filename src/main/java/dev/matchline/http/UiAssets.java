package dev.matchline.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Classpath-only, allowlisted UI assets. No filesystem traversal or dynamic templates. */
final class UiAssets {
    private record Asset(String resource, String contentType, boolean preview) { }

    private static final Map<String, Asset> ASSETS = Map.ofEntries(
            Map.entry("/lab", new Asset("/ui/lab.html", "text/html; charset=utf-8", false)),
            Map.entry("/lab/", new Asset("/ui/lab.html", "text/html; charset=utf-8", false)),
            Map.entry("/embed", new Asset("/ui/lab.html", "text/html; charset=utf-8", true)),
            Map.entry("/embed/", new Asset("/ui/lab.html", "text/html; charset=utf-8", true)),
            Map.entry("/preview", new Asset("/ui/preview.html", "text/html; charset=utf-8", true)),
            Map.entry("/preview/", new Asset("/ui/preview.html", "text/html; charset=utf-8", true)),
            Map.entry("/monitor", new Asset("/ui/monitor.html", "text/html; charset=utf-8", false)),
            Map.entry("/monitor/", new Asset("/ui/monitor.html", "text/html; charset=utf-8", false)),
            Map.entry("/ui/app.css", new Asset("/ui/app.css", "text/css; charset=utf-8", false)),
            Map.entry("/ui/app.mjs", new Asset("/ui/app.mjs", "text/javascript; charset=utf-8", false)),
            Map.entry("/ui/monitor.css", new Asset("/ui/monitor.css", "text/css; charset=utf-8", false)),
            Map.entry("/ui/monitor.mjs", new Asset("/ui/monitor.mjs", "text/javascript; charset=utf-8", false)),
            Map.entry("/ui/mark.svg", new Asset("/ui/mark.svg", "image/svg+xml", false))
    );

    private UiAssets() { }

    static boolean serves(String path) {
        return ASSETS.containsKey(path);
    }

    static void send(HttpExchange exchange, String path, String embedOrigin, boolean publicDemo) throws IOException {
        Asset asset = ASSETS.get(path);
        if (asset == null) throw new IllegalArgumentException("not an allowlisted UI asset");
        // Script/style/connect sources must all be this Java service. Frame permission is
        // intentionally narrower than CORS; only /preview and /embed are embeddable.
        String ancestors = asset.preview() && embedOrigin != null
                && (!path.startsWith("/embed") || publicDemo)
                ? embedOrigin : "'none'";
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                        + "img-src 'self'; font-src 'self'; base-uri 'none'; form-action 'none'; "
                        + "object-src 'none'; frame-ancestors " + ancestors);
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        exchange.getResponseHeaders().set("Content-Type", asset.contentType());
        byte[] bytes;
        try (InputStream stream = UiAssets.class.getResourceAsStream(asset.resource())) {
            if (stream == null) throw new IOException("UI resource missing from application JAR");
            bytes = stream.readAllBytes();
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
