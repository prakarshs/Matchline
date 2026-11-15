package dev.matchline.sequencer;

import dev.matchline.book.LimitOrderBook;
import dev.matchline.book.RestingOrderView;
import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.CommandRejection;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderCommand;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderValidationException;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;

import java.time.Duration;
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
 * <p>A completion means the in-memory decision is finished. All state is volatile:
 * on crash, accepted/completed commands can be lost. Commit 07 will define the
 * journal/fsync/acknowledgement boundary. This is NOT a real exchange.
 *
 * <p>Completion callbacks may execute inline on the writer thread: NEVER do
 * blocking work in non-async dependent actions. Use thenApplyAsync/whenCompleteAsync
 * for slow user work. Do not call close() from an inline completion callback.
 */
public final class SingleWriterSequencer implements AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 1_024;
    public static final int MAX_QUEUE_CAPACITY = 65_536;

    private final Object admissionLock = new Object();
    private final LimitOrderBook book;
    private final ArrayBlockingQueue<WorkItem> queue;
    private final int capacity;
    private final Consumer<OrderCommand> beforeCommand;
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
        this(instrument, queueCapacity, maxRestingOrders, maxOrderIds, ignored -> { });
    }

    // Package-private controlled scheduler hook for deterministic concurrency tests.
    // Production constructors always install a no-op and expose no book reference.
    SingleWriterSequencer(InstrumentId instrument, int queueCapacity,
                          int maxRestingOrders, int maxOrderIds,
                          Consumer<OrderCommand> beforeCommand) {
        if (queueCapacity < 1 || queueCapacity > MAX_QUEUE_CAPACITY) {
            throw new IllegalArgumentException("queueCapacity must be 1.." + MAX_QUEUE_CAPACITY);
        }
        this.book = new LimitOrderBook(Objects.requireNonNull(instrument, "instrument"),
                maxRestingOrders, maxOrderIds);
        this.beforeCommand = Objects.requireNonNull(beforeCommand, "beforeCommand");
        this.capacity = queueCapacity;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.writer = Thread.ofPlatform()
                .name("matchline-writer-" + instrument.value())
                .daemon(true)
                .start(this::runWriter);
    }

    public InstrumentId instrument() {
        return book.instrument(); // Immutable value; never reads mutable book state.
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
            if (!queue.offer(new CommandWork(candidate, command, completion))) {
                throw new OrderValidationException(RejectionCode.INGRESS_OVERLOADED,
                        "bounded sequencer inbox is full");
            }
            lastAdmittedSequence = candidate;
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
                throw new OrderValidationException(RejectionCode.INGRESS_OVERLOADED,
                        "bounded sequencer inbox is full");
            }
            return future;
        }
    }

    private void ensureAccepting() {
        if (!accepting) {
            throw new OrderValidationException(RejectionCode.INGRESS_CLOSED,
                    "sequencer is closed to new work");
        }
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
        }
    }

    private void execute(CommandWork work) {
        beforeCommand.accept(work.command());
        CommandDecision decision;
        try {
            if (work.command() instanceof PlaceLimitOrder place) {
                decision = new CommandDecision.Placed(book.executeLimit(place));
            } else if (work.command() instanceof CancelOrder cancel) {
                decision = new CommandDecision.Cancelled(book.cancel(cancel));
            } else {
                throw new IllegalStateException("unrecognized sealed OrderCommand type");
            }
        } catch (OrderValidationException rejected) {
            decision = new CommandDecision.Rejected(CommandRejection.fromValidation(rejected));
        }
        lastProcessedSequence = work.sequence();
        work.completion().complete(new SequencedResult(work.sequence(), work.command(), decision));
    }

    private void failStop(WorkItem current, Throwable error) {
        // Collect under admission lock so no producer can sneak an unfailed
        // item into the queue, then resolve futures OUTSIDE that lock.
        java.util.ArrayList<WorkItem> abandoned = new java.util.ArrayList<>();
        synchronized (admissionLock) {
            accepting = false;
            fatalFailure = error;
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
                               CompletableFuture<SequencedResult> completion) implements WorkItem {
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
