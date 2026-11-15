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

class PublicDemoHttpTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final String ORDER = "{\"instrument\":\"DEMO\",\"orderId\":\"B1\",\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":4}";
    private static final String PORTFOLIO = "https://portfolio.example.org";

    private static HttpConfig publicConfig() throws Exception {
        return new HttpConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),
                null,null,null,null, DurabilityPolicy.SYNC_EACH_RECORD,64,PORTFOLIO,true);
    }
    private static HttpResponse<String> send(MatchlineHttpServer server, String path,
                                             String method, String token, String json) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.address().getPort() + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("X-Matchline-Session", token);
        if (json != null) builder.header("Content-Type","application/json");
        builder.method(method,json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return CLIENT.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String session(MatchlineHttpServer server, boolean preview) throws Exception {
        var response=send(server,"/api/v1/session"+(preview?"?mode=preview":""),"POST",null,null);
        assertEquals(201,response.statusCode());
        String json=response.body();
        String marker="\"sessionToken\":\"";
        int start=json.indexOf(marker);
        assertTrue(start>=0,json);
        start+=marker.length();
        return json.substring(start,json.indexOf('"',start));
    }

    @Test void missingOrInvalidSessionNeverMutatesTheBook() throws Exception {
        try(var server=new MatchlineHttpServer(publicConfig())) {
            server.start();
            assertEquals(401,send(server,"/api/v1/orders","POST",null,ORDER).statusCode());
            String first=session(server,false);
            assertEquals(401,send(server,"/api/v1/orders","POST","invalid",ORDER).statusCode());
            assertTrue(send(server,"/api/v1/dashboard?depth=5","GET",first,null).body()
                    .contains("\"asOfSequence\":0"));
        }
    }

    @Test void twoBrowsersNeverShareBookIdsOrTrades() throws Exception {
        try(var server=new MatchlineHttpServer(publicConfig())) {
            server.start();
            String a=session(server,false),b=session(server,false);
            assertNotEquals(a,b);
            assertEquals(200,send(server,"/api/v1/orders","POST",a,ORDER).statusCode());
            assertTrue(send(server,"/api/v1/dashboard?depth=5","GET",a,null).body()
                    .contains("\"asOfSequence\":1"));
            assertTrue(send(server,"/api/v1/dashboard?depth=5","GET",b,null).body()
                    .contains("\"asOfSequence\":0"));
            assertEquals(404,send(server,"/api/v1/orders/B1","GET",b,null).statusCode());
            // The same order ID is legal independently in B's book.
            assertEquals(200,send(server,"/api/v1/orders","POST",b,ORDER).statusCode());
            assertTrue(send(server,"/api/v1/dashboard?depth=5","GET",b,null).body()
                    .contains("\"asOfSequence\":1"));
        }
    }

    @Test void previewUsesRealSeededJavaTradesNotScriptedUiOutcomes() throws Exception {
        try(var server=new MatchlineHttpServer(publicConfig())) {
            server.start();
            String preview=session(server,true);
            var dashboard=send(server,"/api/v1/dashboard?depth=5","GET",preview,null);
            assertEquals(200,dashboard.statusCode());
            assertTrue(dashboard.body().contains("\"asOfSequence\":5"));
            assertTrue(dashboard.body().contains("\"makerOrderId\":\"SHOW-S1\""));
            assertTrue(dashboard.body().contains("\"takerOrderId\":\"SHOW-T1\""));
        }
    }

    @Test void publicModeRefusesGlobalResetEvenWithGuessedBearer() throws Exception {
        try(var server=new MatchlineHttpServer(publicConfig())) {
            server.start();
            String session=session(server,false);
            assertEquals(404,send(server,"/api/v1/demo/reset","POST",session,null).statusCode());
        }
    }

    @Test void frameAncestorsOnlyAllowsKnownPortfolioForPreviewAndEmbed() throws Exception {
        try(var server=new MatchlineHttpServer(publicConfig())) {
            server.start();
            for(String path:new String[]{"/preview","/embed"}) {
                var response=send(server,path,"GET",null,null);
                assertEquals(200,response.statusCode());
                assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow()
                        .contains("frame-ancestors "+PORTFOLIO));
            }
            for(String path:new String[]{"/lab","/api/v1/health","/ui/app.mjs"}) {
                assertTrue(send(server,path,"GET",null,null).headers()
                        .firstValue("Content-Security-Policy").orElseThrow()
                        .contains("frame-ancestors 'none'"));
            }
        }
    }

    @Test void browserDashboardDoesNotConsumeCommandSequences() throws Exception {
        try(var server=new MatchlineHttpServer(publicConfig())) {
            server.start();
            String session=session(server,false);
            for(int i=0;i<5;i++) assertEquals(200,send(server,"/api/v1/dashboard?depth=5","GET",session,null).statusCode());
            var result=send(server,"/api/v1/orders","POST",session,ORDER);
            assertEquals(200,result.statusCode());
            assertTrue(result.body().contains("\"sequence\":1"));
        }
    }

    @Test void capacityIsRejectedWithoutEvictingActiveVisitors() throws Exception {
        try(var server=new MatchlineHttpServer(publicConfig())) {
            server.start();
            String first=null;
            for(int i=0;i<DemoSessions.MAX_SESSIONS;i++) {
                String token=session(server,false);
                if(first==null)first=token;
            }
            var rejected=send(server,"/api/v1/session","POST",null,null);
            assertEquals(429,rejected.statusCode());
            assertTrue(rejected.body().contains("SESSION_CAPACITY"));
            assertEquals(200,send(server,"/api/v1/dashboard?depth=5","GET",first,null).statusCode());
        }
    }

    @Test void publicModeConflictsWithJournalingCorsOrReset() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new HttpConfig(
                new InetSocketAddress(InetAddress.getLoopbackAddress(),0),null,null,
                java.nio.file.Path.of("journal.mlj"),null,DurabilityPolicy.SYNC_EACH_RECORD,64,null,true));
        assertThrows(IllegalArgumentException.class,()->new HttpConfig(
                new InetSocketAddress(InetAddress.getLoopbackAddress(),0),"https://other.example",null,
                null,null,DurabilityPolicy.SYNC_EACH_RECORD,64,null,true));
        assertThrows(IllegalArgumentException.class,()->new HttpConfig(
                new InetSocketAddress(InetAddress.getLoopbackAddress(),0),null,"a".repeat(32),
                null,null,DurabilityPolicy.SYNC_EACH_RECORD,64,null,true));
    }

    @Test void hostingPortFallsBackToPortEnvironmentAndOverrideWins() {
        assertEquals(18765,HttpConfig.fromEnvironment(Map.of("PORT","18765")).bind().getPort());
        assertEquals(18766,HttpConfig.fromEnvironment(Map.of("PORT","18765","MATCHLINE_HTTP_PORT","18766")).bind().getPort());
        assertTrue(HttpConfig.fromEnvironment(Map.of("MATCHLINE_PUBLIC_DEMO","true")).publicDemo());
        assertThrows(IllegalArgumentException.class,()->HttpConfig.fromEnvironment(Map.of("MATCHLINE_PUBLIC_DEMO","TRUE")));
    }

    @Test void defaultLocalModeStillWorksWithoutSessionToken() throws Exception {
        var local=new HttpConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),null,null,
                null,null,DurabilityPolicy.SYNC_EACH_RECORD,64);
        try(var server=new MatchlineHttpServer(local)) {
            server.start();
            assertEquals(200,send(server,"/api/v1/dashboard?depth=5","GET",null,null).statusCode());
            assertEquals(200,send(server,"/api/v1/orders","POST",null,ORDER).statusCode());
            assertEquals(404,send(server,"/api/v1/session","POST",null,null).statusCode());
        }
    }
}
