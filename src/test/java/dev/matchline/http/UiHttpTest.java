package dev.matchline.http;

import dev.matchline.journal.DurabilityPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A real Java HTTP server serves same-origin, classpath-only HTML/CSS/JS. */
class UiHttpTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final String PORTFOLIO = "https://portfolio.example.org";

    private static HttpConfig config(String embedOrigin) throws Exception {
        return new HttpConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null, null, null, null, DurabilityPolicy.SYNC_EACH_RECORD, 128, embedOrigin);
    }
    private static HttpResponse<String> get(MatchlineHttpServer server, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.address().getPort() + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void labHtmlReferencesOnlySameOriginAssetsAndActualApi() throws Exception {
        try (var server = new MatchlineHttpServer(config(null))) {
            server.start();
            var page = get(server, "/lab");
            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("content-type").orElseThrow().startsWith("text/html"));
            assertTrue(page.body().contains("id=\"order-form\""));
            assertTrue(page.body().contains("id=\"scenario-step\""));
            assertTrue(page.body().contains("/ui/app.mjs"));
            assertTrue(page.body().contains("/ui/app.css"));
            assertFalse(page.body().contains("https://fonts.googleapis.com"));
            assertEquals("no-store",page.headers().firstValue("cache-control").orElseThrow());
        }
    }

    @Test void previewIsReadOnlyAndNonEmbeddableByDefault() throws Exception {
        try (var server = new MatchlineHttpServer(config(null))) {
            server.start();
            var preview = get(server, "/preview");
            assertEquals(200, preview.statusCode());
            assertTrue(preview.body().contains("data-view=\"preview\""));
            assertFalse(preview.body().contains("id=\"order-form\""));
            assertFalse(preview.body().contains("id=\"cancel-form\""));
            assertFalse(preview.body().contains("id=\"scenario-step\""));
            assertTrue(preview.headers().firstValue("content-security-policy").orElseThrow()
                    .contains("frame-ancestors 'none'"));
        }
    }

    @Test void onlyPreviewPermitsExplicitPortfolioEmbedding() throws Exception {
        try (var server = new MatchlineHttpServer(config(PORTFOLIO))) {
            server.start();
            String preview = get(server, "/preview/").headers()
                    .firstValue("content-security-policy").orElseThrow();
            assertTrue(preview.contains("frame-ancestors " + PORTFOLIO));
            assertFalse(preview.contains("'unsafe-inline'"));
            String lab = get(server, "/lab/").headers()
                    .firstValue("content-security-policy").orElseThrow();
            assertTrue(lab.contains("frame-ancestors 'none'"));
            String api = get(server, "/api/v1/health").headers()
                    .firstValue("content-security-policy").orElseThrow();
            assertTrue(api.contains("frame-ancestors 'none'"));
            assertTrue(api.contains("default-src 'none'"));
        }
    }

    @Test void scriptsStylesAndIconsArePackagedWithNoExternalDependencies() throws Exception {
        try (var server = new MatchlineHttpServer(config(null))) {
            server.start();
            for (String path : new String[]{"/ui/app.mjs", "/ui/app.css", "/ui/mark.svg"}) {
                var asset = get(server, path);
                assertEquals(200,asset.statusCode(),path);
                assertTrue(asset.body().length()>100,path);
                assertEquals("nosniff",asset.headers().firstValue("x-content-type-options").orElseThrow());
                assertTrue(asset.headers().firstValue("content-security-policy").orElseThrow()
                        .contains("script-src 'self'"));
            }
            assertTrue(get(server,"/ui/app.mjs").body().contains("/api/v1/book"));
            assertTrue(get(server,"/ui/app.css").body().contains(".preview-shell"));
        }
    }

    @Test void traversalAndMissingUiAssetsAreNotServed() throws Exception {
        try (var server = new MatchlineHttpServer(config(null))) {
            server.start();
            assertEquals(404,get(server,"/ui/secrets.env").statusCode());
            assertEquals(404,get(server,"/ui/../README.md").statusCode());
            assertEquals(404,get(server,"/lab/test").statusCode());
        }
    }

    @Test void invalidFrameOriginIsRejectedAndNoWildcardIsSupported() throws Exception {
        for (String bad : new String[]{"*","file:///tmp/portfolio.html","https://x.example/path",
                "https://x.example/?a=1","https://x.example/#fragment","not-an-origin"}) {
            assertThrows(IllegalArgumentException.class,()->config(bad),bad);
        }
        assertEquals(PORTFOLIO,HttpConfig.fromEnvironment(Map.of("MATCHLINE_EMBED_ORIGIN",PORTFOLIO))
                .embedOrigin());
    }

    @Test void UIResponsesDoNotMutateTheEngine() throws Exception {
        try (var server = new MatchlineHttpServer(config(null))) {
            server.start();
            get(server,"/lab");get(server,"/preview");get(server,"/ui/app.css");
            assertTrue(get(server,"/api/v1/status").body().contains("\"lastProcessedSequence\":0"));
        }
    }

    @Test void FullLabCanReadAndMutateViaSameOriginApi() throws Exception {
        try (var server = new MatchlineHttpServer(config(null))) {
            server.start();
            assertEquals(200,get(server,"/lab").statusCode());
            var place=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + server.address().getPort()+"/api/v1/orders"))
                    .header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"instrument\":\"DEMO\",\"orderId\":\"UI-B1\",\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":5}"))
                    .build();
            assertEquals(200,CLIENT.send(place,HttpResponse.BodyHandlers.ofString()).statusCode());
            var book=get(server,"/api/v1/book?depth=5");
            assertEquals(200,book.statusCode());
            assertTrue(book.body().contains("\"orderId\":\"UI-B1\""));
            assertTrue(book.body().contains("\"asOfSequence\":1"));
        }
    }
}
