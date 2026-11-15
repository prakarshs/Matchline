package dev.matchline.recovery;

import java.nio.file.Path;
import java.util.Objects;

/** Opt-in periodic, blocking-on-writer snapshot persistence at decision boundaries. */
public record SnapshotSchedule(Path path, long everyDecisions) {
    public SnapshotSchedule {
        Objects.requireNonNull(path, "path");
        if (everyDecisions < 1) throw new IllegalArgumentException("everyDecisions must be positive");
    }
}
