package dev.matchline.benchmark;

import dev.matchline.domain.OrderCommand;
import dev.matchline.journal.DurabilityPolicy;
import dev.matchline.journal.FileCommandJournal;
import dev.matchline.sequencer.SingleWriterSequencer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 100 commands/op; file creation, journal header, append, (optional) record fsync,
 * close and cleanup included. Storage-dependent, not a pure fsync microbenchmark.
 * Run separately with -p durability=SYNC_EACH_RECORD or OS_BUFFERED.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 2)
@Fork(1)
@Threads(1)
public class JournalBenchmark {
    private static final int COMMANDS = 100;

    @State(Scope.Thread)
    public static class Workload {
        @Param({"SYNC_EACH_RECORD", "OS_BUFFERED"})
        public String durability;
        List<OrderCommand> commands;

        @Setup(Level.Trial)
        public void setup() {
            commands = DeterministicWorkload.commands(DeterministicWorkload.Scenario.CROSSING, COMMANDS);
        }
    }

    @Benchmark
    @OperationsPerInvocation(COMMANDS)
    public long batch(Workload workload) throws IOException {
        Path directory = Files.createTempDirectory("matchline-jmh-");
        Path path = directory.resolve("commands.mlj");
        long checksum = 0;
        try {
            FileCommandJournal journal = FileCommandJournal.createNew(path,
                    DeterministicWorkload.DEMO, DurabilityPolicy.valueOf(workload.durability));
            try (var writer = new SingleWriterSequencer(DeterministicWorkload.DEMO, 128, 200, 500, journal)) {
                for (OrderCommand command : workload.commands) {
                    var result = writer.submit(command).completion().toCompletableFuture().join();
                    if (!result.successful()) throw new IllegalStateException("journal workload was rejected");
                    checksum += result.sequence();
                }
            }
            return checksum;
        } finally {
            Files.deleteIfExists(path);
            Files.delete(directory);
        }
    }
}
