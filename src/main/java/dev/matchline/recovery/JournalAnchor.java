package dev.matchline.recovery;

import java.util.Objects;

/** Exact completed-decision byte boundary and SHA-256 of the journal prefix. */
public record JournalAnchor(long sequence, long offset, String prefixSha256) {
    public JournalAnchor {
        if (sequence < 0 || offset < 23) throw new IllegalArgumentException("invalid journal anchor");
        Objects.requireNonNull(prefixSha256, "prefixSha256");
        if (!prefixSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("journal prefixSha256 must be lowercase 64-character hex");
        }
    }
}
