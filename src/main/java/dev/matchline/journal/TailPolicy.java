package dev.matchline.journal;

/** Offline-only scan policy. Neither policy silently accepts a complete corrupt frame. */
public enum TailPolicy {
    /** Report even a short final header/payload as an error; never change the file. */
    STRICT,
    /** Truncate only an incomplete final frame after validating all preceding frames. */
    TRUNCATE_INCOMPLETE_TAIL
}
