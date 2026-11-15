package dev.matchline.http;

import dev.matchline.journal.DurabilityPolicy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Immutable, validated listener configuration. Secrets are deliberately omitted from toString(). */
public final class HttpConfig {
    private final InetSocketAddress bind;
    private final String allowedOrigin;
    private final String resetToken;
    private final String embedOrigin;
    private final Path journalPath;
    private final Path snapshotPath;
    private final DurabilityPolicy durability;
    private final int queueCapacity;

    public HttpConfig(InetSocketAddress bind, String allowedOrigin, String resetToken,
                      Path journalPath, Path snapshotPath, DurabilityPolicy durability, int queueCapacity) {
        this(bind, allowedOrigin, resetToken, journalPath, snapshotPath, durability,
                queueCapacity, null);
    }

    public HttpConfig(InetSocketAddress bind, String allowedOrigin, String resetToken,
                      Path journalPath, Path snapshotPath, DurabilityPolicy durability,
                      int queueCapacity, String embedOrigin) {
        this.bind = Objects.requireNonNull(bind, "bind");
        if (bind.isUnresolved() || bind.getPort() < 0 || bind.getPort() > 65535) {
            throw new IllegalArgumentException("HTTP bind address must resolve and port must be 0..65535");
        }
        if (allowedOrigin != null && !allowedOrigin.isBlank()) {
            try {
                URI uri = URI.create(allowedOrigin);
                if (uri.getHost() == null || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        || !uri.toString().equals(allowedOrigin) || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException("CORS origin must be one exact http(s) origin");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("CORS origin must be one exact http(s) origin", invalid);
            }
        }
        if (embedOrigin != null && !embedOrigin.isBlank()) {
            try {
                URI uri = URI.create(embedOrigin);
                if (uri.getHost() == null || uri.getUserInfo() != null
                        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                        || !uri.toString().equals(embedOrigin)) {
                    throw new IllegalArgumentException("not a valid origin");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("embed origin must be one exact http(s) origin", invalid);
            }
        }
        if (resetToken != null && !resetToken.isEmpty() && resetToken.length() < 32) {
            throw new IllegalArgumentException("reset token must contain at least 32 characters");
        }
        if (snapshotPath != null && journalPath == null) {
            throw new IllegalArgumentException("snapshot path requires a journal path");
        }
        if (queueCapacity < 1 || queueCapacity > 65_536) {
            throw new IllegalArgumentException("queue capacity must be 1..65536");
        }
        this.allowedOrigin = allowedOrigin == null || allowedOrigin.isBlank() ? null : allowedOrigin;
        this.embedOrigin = embedOrigin == null || embedOrigin.isBlank() ? null : embedOrigin;
        this.resetToken = resetToken == null || resetToken.isBlank() ? null : resetToken;
        this.journalPath = journalPath == null ? null : journalPath.toAbsolutePath().normalize();
        this.snapshotPath = snapshotPath == null ? null : snapshotPath.toAbsolutePath().normalize();
        this.durability = Objects.requireNonNull(durability, "durability");
        this.queueCapacity = queueCapacity;
    }

    public static HttpConfig fromEnvironment(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        String host = env.getOrDefault("MATCHLINE_HTTP_HOST", "127.0.0.1");
        int port = Integer.parseInt(env.getOrDefault("MATCHLINE_HTTP_PORT", "8080"));
        try {
            InetAddress address = InetAddress.getByName(host);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be 1..65535");
            return new HttpConfig(new InetSocketAddress(address, port),
                    env.get("MATCHLINE_ALLOWED_ORIGIN"), env.get("MATCHLINE_RESET_TOKEN"),
                    env.containsKey("MATCHLINE_JOURNAL_PATH") ? Path.of(env.get("MATCHLINE_JOURNAL_PATH")) : null,
                    env.containsKey("MATCHLINE_SNAPSHOT_PATH") ? Path.of(env.get("MATCHLINE_SNAPSHOT_PATH")) : null,
                    DurabilityPolicy.valueOf(env.getOrDefault("MATCHLINE_DURABILITY", "SYNC_EACH_RECORD")),
                    Integer.parseInt(env.getOrDefault("MATCHLINE_QUEUE_CAPACITY", "1024")),
                    env.get("MATCHLINE_EMBED_ORIGIN"));
        } catch (UnknownHostException badHost) {
            throw new IllegalArgumentException("invalid HTTP bind host", badHost);
        }
    }

    public InetSocketAddress bind() { return bind; }
    public String allowedOrigin() { return allowedOrigin; }
    public String embedOrigin() { return embedOrigin; }
    public String resetToken() { return resetToken; }
    public Path journalPath() { return journalPath; }
    public Path snapshotPath() { return snapshotPath; }
    public DurabilityPolicy durability() { return durability; }
    public int queueCapacity() { return queueCapacity; }

    @Override public String toString() {
        return "HttpConfig[bind=" + bind + ", journal=" + (journalPath != null)
                + ", cors=" + (allowedOrigin != null) + ", embed=" + (embedOrigin != null)
                + ", reset=" + (resetToken != null) + "]";
    }
}
