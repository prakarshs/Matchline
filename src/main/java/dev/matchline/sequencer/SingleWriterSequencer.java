package dev.matchline.sequencer;

import dev.matchline.book.LimitOrderBook;
import dev.matchline.book.BookSnapshot;
import dev.matchline.book.RestingOrderView;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderCommand;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderValidationException;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import dev.matchline.journal.DurabilityPolicy;
import dev.matchline.journal.FileCommandJournal;
import dev.matchline.journal.JournalEntry;
import dev.matchline.journal.JournalScan;
import dev.matchline.recovery.JournalAnchor;
import dev.matchline.recovery.JournalAnchors;
import dev.matchline.recovery.SnapshotSchedule;
import dev.matchline.recovery.SnapshotStore;
import dev.matchline.recovery.RecoveryReport;
import dev.matchline.observability.EngineMetrics;

import java.io.IOException;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One writer and one bounded FIFO inbox per instrument. Owns (never exposes)
 * its mutable {@link LimitOrderBook}. No callers may touch the book off-thread.
 *
 * <p>Linearization point for ingress: a synchronized non-blocking queue offer.
 * On successful admission a gapless sequence is assigned under the same lock;
 * concurrent calls are ordered by lock acquisition, NOT wall-clock timestamps.
 * Overflow and shutdown are rejected before admission and have NO sequence.
 * Business rejections (including duplicate IDs, wrong instrument and missing
 * cancel targets) ARE sequenced and complete with {@link CommandDecision.Rejected}.
 *
 * <p>Without a journal, completion remains volatile. With a fresh journal attached,
 * each command is appended before book mutation and each decision afterward.
 * SYNC_EACH_RECORD completion confirms both frames were forced to disk;
 * OS_BUFFERED completion does not. Recovery validates the journal, restores
 * optional snapshots and resumes this same single-writer command path.
 * This is NOT a real exchange.
 *
 * <p>Completion callbacks may execute inline on the writer thread: NEVER do
 * blocking work in non-async dependent actions. Use thenApplyAsync/whenCompleteAsync
 * for slow user work. Do not call close() from an inline completion callback.
 */
public final class SingleWriterSequencer implements AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 1_024;
    public static final int MAX_QUEUE_CAPACITY = 65_536;

    private final Object admissionLock = new Object();
    private final EngineMetrics metrics = new EngineMetrics();
    private final LimitOrderBook book;
    private final ArrayBlockingQueue<WorkItem> queue;
    private final int capacity;
    private final Consumer<OrderCommand> beforeCommand;
    private final FileCommandJournal journal;
    private final SnapshotSchedule snapshots;
    private final Thread writer;
    private volatile boolean accepting = true;
    private volatile long lastAdmittedSequence;
    private volatile long lastProcessedSequence;
    private volatile Throwable fatalFailure;

    public SingleWriterSequencer(InstrumentId instrument) {
        this(instrument, DEFAULT_QUEUE_CAPACITY);
    }

    public SingleWriterSequencer(InstrumentId instrument, int queueCapacity) {
        this(instrument, queueCapacity, LimitOrderBook.DEFAULT_MAX_RESTING_ORDERS,
                LimitOrderBook.DEFAULT_MAX_ORDER_IDS);
    }

    public SingleWriterSequencer(InstrumentId instrument, int queueCapacity,
                                 int maxRestingOrders, int maxOrderIds) {
        this(instrument, queueCapacity, maxRestingOrders, maxOrderIds, ignored -> { }, null);
    }

    /**
     * Attach a FRESH journal before any commands are admitted. The sequencer
     * owns and closes it. Existing files must use the static recover() factory.
     */
    public SingleWriterSequencer(InstrumentId instrument, int queueCapacity,
                                 int maxRestingOrders, int maxOrderIds,
                                 FileCommandJournal journal) {
        this(instrument, queueCapacity, maxRestingOrders, maxOrderIds,
                ignored -> { }, Objects.requireNonNull(journal, "journal"));
    }

    /**
     * Persist a full snapshot every N completed decisions on the writer thread.
     * Opt-in: snapshot serialization/fsync can increase command latency.
     * Snapshot file must not be the journal file, and its directory must exist.
     */
    public SingleWriterSequencer(InstrumentId instrument, int queueCapacity,
                                 int maxRestingOrders, int maxOrderIds,
                                 FileCommandJournal journal, SnapshotSchedule snapshots) {
        this(new LimitOrderBook(instrument, maxRestingOrders, maxOrderIds), queueCapacity,
                ignored -> { }, Objects.requireNonNull(journal, "journal"),
                Objects.requireNonNull(snapshots, "snapshots"), 0L, false);
    }

    /**
     * Reopen a journal, verify each historic decision against deterministic
     * execution, optionally restore an anchored snapshot, resolve a single
     * pending command, and only THEN start a writer accepting new commands.
     * No implicit tail repair; a torn tail requires an explicit offline scan.
     */
    public static RecoveredSequencer recover(Path journalPath, InstrumentId instrument,
                                             int queueCapacity, int maxRestingOrders, int maxOrderIds,
                                             DurabilityPolicy policy, SnapshotSchedule snapshots)
            throws IOException {
        FileCommandJournal journal = FileCommandJournal.openExisting(journalPath, instrument, policy);
        try {
            checkSnapshotPath(snapshots, journal);
            JournalScan scan = journal.existingScan();
            long checkpoint = 0;
            LimitOrderBook restored;
            if (snapshots != null && Files.exists(snapshots.path())) {
                SnapshotStore.Stored stored = SnapshotStore.read(snapshots.path());
                BookSnapshot state = stored.state();
                if (!state.instrument().equals(instrument)) {
                    throw new IOException("snapshot instrument differs from journal");
                }
                JournalAnchor actual = JournalAnchors.atCompletedSequence(
                        journalPath, scan, state.lastSequence());
                if (!actual.equals(stored.anchor())) {
                    throw new IOException("snapshot journal prefix/decision boundary mismatch");
                }
                try {
                    restored = LimitOrderBook.restore(state, maxRestingOrders, maxOrderIds);
                } catch (RuntimeException invalid) {
                    throw new IOException("snapshot cannot be restored with supplied configuration", invalid);
                }
                checkpoint = state.lastSequence();
            } else {
                restored = new LimitOrderBook(instrument, maxRestingOrders, maxOrderIds);
            }
            List<JournalEntry> entries = scan.entries();
            long replayed = 0;
            for (long sequence = checkpoint + 1; sequence <= scan.completedDecisionCount(); sequence++) {
                int index = Math.toIntExact(2L * (sequence - 1));
                if (!(entries.get(index) instanceof JournalEntry.Command command)
                        || !(entries.get(index + 1) instanceof JournalEntry.Decision recorded)) {
                    throw new IOException("journal decision pair is missing at sequence " + sequence);
                }
                CommandDecision computed = CommandProcessor.apply(restored, command.command());
                if (!computed.equals(recorded.decision())) {
                    throw new IOException("replay decision differs from journal at sequence " + sequence);
                }
                replayed++;
            }
            boolean resolvedPending = scan.hasUnacknowledgedCommand();
            long finalSequence = scan.completedDecisionCount();
            if (resolvedPending) {
                JournalEntry last = entries.get(entries.size() - 1);
                if (!(last instanceof JournalEntry.Command pending)
                        || pending.sequence() != finalSequence + 1) {
                    throw new IOException("invalid final pending journal command");
                }
                // Previously applied volatile effects cannot survive a restart.
                // Recompute from validated committed history, then append the
                // missing decision once, BEFORE admitting fresh commands.
                CommandDecision decision = CommandProcessor.apply(restored, pending.command());
                journal.appendDecision(pending.sequence(), decision);
                finalSequence = pending.sequence();
            }
            SingleWriterSequencer sequencer = new SingleWriterSequencer(restored, queueCapacity,
                    ignored -> { }, journal, snapshots, finalSequence, true);
            return new RecoveredSequencer(sequencer,
                    new RecoveryReport(checkpoint, replayed, finalSequence, resolvedPending));
        } catch (Throwable failed) {
            try {
                journal.close();
            } catch (IOException closeFailure) {
                failed.addSuppressed(closeFailure);
            }
            if (failed instanceof IOException io) throw io;
            throw new IOException("journal recovery failed", failed);
        }
    }

    private static void checkSnapshotPath(SnapshotSchedule schedule, FileCommandJournal journal)
            throws IOException {
        if (schedule == null) return;
        Path snapshot = schedule.path().toAbsolutePath().normalize();
        if (snapshot.equals(journal.path()) || (Files.exists(snapshot)
                && Files.isSameFile(snapshot, journal.path()))) {
            throw new IOException("snapshot path must not alias the journal path");
        }
        if (snapshot.getParent() == null || !Files.isDirectory(snapshot.getParent())) {
            throw new IOException("snapshot parent directory must exist before startup");
        }
    }

    // Package-private controlled scheduler hook for deterministic concurrency tests.
    // Production constructors always install a no-op and expose no book reference.
    SingleWriterSequencer(InstrumentId instrument, int queueCapacity,
                          int maxRestingOrders, int maxOrderIds,
                          Consumer<OrderCommand> beforeCommand) {
        this(instrument, queueCapacity, maxRestingOrders, maxOrderIds, beforeCommand, null);
    }

    private SingleWriterSequencer(InstrumentId instrument, int queueCapacity,
                                  int maxRestingOrders, int maxOrderIds,
                                  Consumer<OrderCommand> beforeCommand,
                                  FileCommandJournal journal) {
        this(new LimitOrderBook(instrument, maxRestingOrders, maxOrderIds), queueCapacity,
                beforeCommand, journal, null, 0L, false);
    }

    private SingleWriterSequencer(LimitOrderBook book, int queueCapacity,
                                  Consumer<OrderCommand> beforeCommand,
                                  FileCommandJournal journal, SnapshotSchedule snapshots,
                                  long initialSequence, boolean recovered) {
        if (queueCapacity < 1 || queueCapacity > MAX_QUEUE_CAPACITY) {
            throw new IllegalArgumentException("queueCapacity must be 1.." + MAX_QUEUE_CAPACITY);
        }
        this.book = Objects.requireNonNull(book, "book");
        this.beforeCommand = Objects.requireNonNull(beforeCommand, "beforeCommand");
        if (snapshots != null && journal == null) {
            throw new IllegalArgumentException("periodic snapshots require a journal");
        }
        if (journal != null) {
            try {
                checkSnapshotPath(snapshots, journal);
            } catch (IOException io) {
                throw new IllegalArgumentException("snapshot path conflicts with journal", io);
            }
            if (!recovered && snapshots != null && Files.exists(snapshots.path())) {
                throw new IllegalArgumentException("fresh journal must not overwrite an existing snapshot");
            }
            if (recovered) journal.claimRecovered(book.instrument());
            else journal.claimFresh(book.instrument());
        }
        this.journal = journal;
        this.snapshots = snapshots;
        this.capacity = queueCapacity;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.lastAdmittedSequence = initialSequence;
        this.lastProcessedSequence = initialSequence;
        this.writer = Thread.ofPlatform()
                .name("matchline-writer-" + book.instrument().value())
                .daemon(true)
                .start(this::runWriter);
    }

    public InstrumentId instrument() {
        return book.instrument(); // Immutable value; never reads mutable book state.
    }

    /** Empty means this sequencer has only volatile in-memory acknowledgements. */
    public Optional<DurabilityPolicy> journalDurabilityPolicy() {
        return journal == null ? Optional.empty() : Optional.of(journal.durabilityPolicy());
    }

    /**
     * Submit without waiting. A returned ticket means QUEUED, not executed.
     * Full/closed input rejects synchronously with no sequence assignment.
     */
    public CommandTicket submit(OrderCommand command) {
        if (command == null) {
            throw new OrderValidationException(RejectionCode.INVALID_COMMAND, "command is required");
        }
        synchronized (admissionLock) {
            ensureAccepting();
            if (lastAdmittedSequence == Long.MAX_VALUE) {
                throw new OrderValidationException(RejectionCode.SEQUENCE_EXHAUSTED,
                        "in-memory sequence space exhausted");
            }
            long candidate = lastAdmittedSequence + 1;
            CompletableFuture<SequencedResult> completion = new CompletableFuture<>();
            if (!queue.offer(new CommandWork(candidate, command, completion, System.nanoTime()))) {
                metrics.rejectedFull();
                throw new OrderValidationException(RejectionCode.INGRESS_OVERLOADED,
                        "bounded sequencer inbox is full");
            }
            lastAdmittedSequence = candidate;
            metrics.admittedCommand();
            return new CommandTicket(candidate, completion.minimalCompletionStage());
        }
    }

    /**
     * Enqueue a consistent view behind all previously admitted inbox items.
     * Reads take a bounded inbox slot but do not consume a command sequence.
     */
    public CompletableFuture<BookReadView> inspect(int depthLevels) {
        if (depthLevels < 1 || depthLevels > LimitOrderBook.MAX_DEPTH_LEVELS) {
            throw new IllegalArgumentException("depthLevels must be 1.." + LimitOrderBook.MAX_DEPTH_LEVELS);
        }
        return submitRead(() -> new BookReadView(book.instrument(), lastProcessedSequence,
                book.restingOrderCount(), book.acceptedOrderIdCount(),
                book.depth(Side.BUY, depthLevels), book.depth(Side.SELL, depthLevels),
                book.bestOrder(Side.BUY), book.bestOrder(Side.SELL)));
    }

    /** Detached complete state including accepted-ID history, read after earlier admitted work. */
    public CompletableFuture<BookSnapshot> snapshotState() {
        return submitRead(() -> book.snapshot(lastProcessedSequence));
    }

    /** Consistent immutable FIFO views at one exact price, on the writer. */
    public CompletableFuture<List<RestingOrderView>> ordersAt(Side side, PriceTicks price) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(price, "price");
        return submitRead(() -> book.ordersAt(side, price));
    }

    /** Consistent immutable lookup, on the writer. */
    public CompletableFuture<Optional<RestingOrderView>> find(OrderId id) {
        Objects.requireNonNull(id, "id");
        return submitRead(() -> book.find(id));
    }

    private <T> CompletableFuture<T> submitRead(Supplier<T> read) {
        synchronized (admissionLock) {
            ensureAccepting();
            CompletableFuture<T> future = new CompletableFuture<>();
            if (!queue.offer(new ReadWork<>(read, future))) {
                metrics.rejectedFull();
                throw new OrderValidationException(RejectionCode.INGRESS_OVERLOADED,
                        "bounded sequencer inbox is full");
            }
            metrics.admittedRead();
            return future;
        }
    }

    private void ensureAccepting() {
        if (!accepting) {
            metrics.rejectedClosed();
            throw new OrderValidationException(RejectionCode.INGRESS_CLOSED,
                    "sequencer is closed to new work");
        }
    }

    /** Weakly consistent fixed-memory performance counters (no changes to sequencing). */
    public EngineMetrics.Snapshot metricsSnapshot() {
        return metrics.snapshot();
    }

    /** Highest sequence successfully admitted into the volatile inbox. */
    public long lastAdmittedSequence() {
        return lastAdmittedSequence;
    }

    /** Highest command sequence whose decision was completed in writer order. */
    public long lastProcessedSequence() {
        return lastProcessedSequence;
    }

    /** Approximate number queued (excludes the one item currently executing). */
    public int queuedWorkCount() {
        return queue.size();
    }

    public int queueCapacity() {
        return capacity;
    }

    public boolean accepting() {
        return accepting;
    }

    /** Failure is non-null only if the writer failed unexpectedly and stopped. */
    public Optional<Throwable> fatalFailure() {
        return Optional.ofNullable(fatalFailure);
    }

    /** Reject new work but drain already admitted commands and reads in FIFO order. */
    public void stopAccepting() {
        synchronized (admissionLock) {
            accepting = false;
        }
        // Writer polls at most 50ms while idle; never interrupt a mutating writer.
    }

    /** Await graceful drain; does not accept new work, and does not stop the writer. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        if (Thread.currentThread() == writer) {
            throw new IllegalStateException("writer cannot wait for itself");
        }
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        if (nanos == 0) {
            return !writer.isAlive();
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
        int remNanos = (int) (nanos - TimeUnit.MILLISECONDS.toNanos(millis));
        writer.join(millis, remNanos);
        return !writer.isAlive();
    }

    /**
     * Stop admission and drain. A stuck client callback may prevent termination;
     * this method reports the timeout rather than silently dropping work.
     */
    @Override
    public void close() {
        stopAccepting();
        try {
            if (!awaitTermination(Duration.ofSeconds(10))) {
                throw new IllegalStateException("sequencer did not drain within 10 seconds");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting sequencer shutdown", interrupted);
        }
    }

    private void runWriter() {
        try {
            while (true) {
                WorkItem item = queue.poll(50, TimeUnit.MILLISECONDS);
                if (item == null) {
                    if (!accepting && queue.isEmpty()) {
                        return;
                    }
                    continue;
                }
                try {
                    if (item instanceof CommandWork work) {
                        execute(work);
                    } else if (item instanceof ReadWork<?> read) {
                        read.execute();
                    } else {
                        throw new IllegalStateException("unhandled sequencer inbox entry");
                    }
                } catch (Throwable unexpected) {
                    // An unexpected exception may have interrupted a book mutation.
                    // Fail STOP, not continue on potentially inconsistent state.
                    failStop(item, unexpected);
                    return;
                }
            }
        } catch (InterruptedException unexpected) {
            Thread.currentThread().interrupt();
            failStop(null, unexpected);
        } finally {
            if (journal != null) {
                try {
                    journal.close();
                } catch (IOException closeFailure) {
                    failStop(null, closeFailure);
                }
            }
        }
    }

    private void execute(CommandWork work) throws IOException {
        long startedNanos = System.nanoTime();
        beforeCommand.accept(work.command());
        // WAL: a command must be recorded before any mutating book operation.
        // A crash after this append but before decision append is an AMBIGUOUS
        // outcome; it is deliberately NOT reported as a successful completion.
        if (journal != null) journal.appendCommand(work.sequence(), work.command());
        CommandDecision decision = CommandProcessor.apply(book, work.command());
        // Synchronous mode forces the outcome before the public completion.
        // An append/force failure after mutation FAIL-STOPS this writer.
        if (journal != null) journal.appendDecision(work.sequence(), decision);
        lastProcessedSequence = work.sequence();
        if (snapshots != null && work.sequence() % snapshots.everyDecisions() == 0) {
            // Snapshot follows the committed decision. If persistence fails the
            // writer fail-stops; the journal remains the recovery authority.
            SnapshotStore.writeAtomically(snapshots.path(), book.snapshot(work.sequence()), journal.checkpoint());
        }
        long completedNanos = System.nanoTime();
        metrics.completedCommand(decision instanceof CommandDecision.Rejected,
                Math.max(0L, startedNanos - work.admittedNanos()),
                Math.max(0L, completedNanos - startedNanos),
                Math.max(0L, completedNanos - work.admittedNanos()));
        work.completion().complete(new SequencedResult(work.sequence(), work.command(), decision));
    }

    private void failStop(WorkItem current, Throwable error) {
        // Collect under admission lock so no producer can sneak an unfailed
        // item into the queue, then resolve futures OUTSIDE that lock.
        java.util.ArrayList<WorkItem> abandoned = new java.util.ArrayList<>();
        synchronized (admissionLock) {
            accepting = false;
            if (fatalFailure == null) {
                fatalFailure = error;
                metrics.failedWriter();
            } else if (fatalFailure != error) {
                fatalFailure.addSuppressed(error);
            }
            if (current != null) {
                abandoned.add(current);
            }
            queue.drainTo(abandoned);
        }
        for (WorkItem pending : abandoned) {
            pending.fail(error);
        }
    }

    private sealed interface WorkItem permits CommandWork, ReadWork {
        void fail(Throwable error);
    }

    private record CommandWork(long sequence, OrderCommand command,
                               CompletableFuture<SequencedResult> completion, long admittedNanos) implements WorkItem {
        @Override
        public void fail(Throwable error) {
            completion.completeExceptionally(error);
        }
    }

    private record ReadWork<T>(Supplier<T> read, CompletableFuture<T> completion) implements WorkItem {
        private void execute() {
            completion.complete(read.get());
        }

        @Override
        public void fail(Throwable error) {
            completion.completeExceptionally(error);
        }
    }
}
