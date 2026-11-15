package dev.matchline.journal;

/** Controls the completion boundary for a journal-attached sequencer. */
public enum DurabilityPolicy {
    /** force(true) the command before applying it, then force(true) the decision before completion. */
    SYNC_EACH_RECORD,
    /** No explicit force; completion is NOT a durable acknowledgement. */
    OS_BUFFERED
}
