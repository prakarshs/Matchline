package dev.matchline.bootstrap;

/**
 * A truthful description of which parts of MATCHLINE exist in this increment.
 * Not an exchange-status or readiness model for the future HTTP service.
 */
public record BootstrapStatus(
        String application,
        String version,
        boolean matchingAvailable,
        boolean httpAvailable,
        boolean journalAvailable
) {
    public static BootstrapStatus current() {
        return new BootstrapStatus("MATCHLINE", "0.1.0-SNAPSHOT", false, false, false);
    }

    public static boolean supportedJavaFeature(int javaFeature) {
        return javaFeature >= 21;
    }
}
