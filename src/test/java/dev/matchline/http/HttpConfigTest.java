package dev.matchline.http;

import dev.matchline.journal.DurabilityPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpConfigTest {
    private final InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    @Test void startsLoopbackWithoutResetOrCorsByDefault() {
        HttpConfig config = HttpConfig.fromEnvironment(Map.of());
        assertEquals("127.0.0.1", config.bind().getAddress().getHostAddress());
        assertEquals(8080, config.bind().getPort());
        assertEquals(null, config.resetToken());
        assertEquals(null, config.allowedOrigin());
    }

    @Test void rejectsUnsafeOriginAndShortResetSecret() {
        assertThrows(IllegalArgumentException.class, () ->
                new HttpConfig(loopback, "*", null, null, null, DurabilityPolicy.SYNC_EACH_RECORD, 10));
        assertThrows(IllegalArgumentException.class, () ->
                new HttpConfig(loopback, "https://site.example/path", null, null, null, DurabilityPolicy.SYNC_EACH_RECORD, 10));
        assertThrows(IllegalArgumentException.class, () ->
                new HttpConfig(loopback, null, "weak", null, null, DurabilityPolicy.SYNC_EACH_RECORD, 10));
    }

    @Test void snapshotsRequireJournalAndToStringNeverLeaksToken() {
        assertThrows(IllegalArgumentException.class, () ->
                new HttpConfig(loopback, null, null, null, Path.of("snapshot.mls"),
                        DurabilityPolicy.SYNC_EACH_RECORD, 10));
        String secret = "abcdefghijklmnopqrstuvwx12345678";
        HttpConfig config = new HttpConfig(loopback, "https://site.example", secret,
                null, null, DurabilityPolicy.OS_BUFFERED, 10);
        assertFalse(config.toString().contains(secret));
        assertTrue(config.toString().contains("reset=true"));
    }
}
