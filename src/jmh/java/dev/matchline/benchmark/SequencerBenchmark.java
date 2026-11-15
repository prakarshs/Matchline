package dev.matchline.benchmark;

import dev.matchline.domain.OrderCommand;
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

import java.util.List;
import java.util.concurrent.TimeUnit;

/** In-memory writer: submit and await each decision. Includes thread creation and close per batch. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
@Threads(1)
public class SequencerBenchmark {
    @State(Scope.Thread)
    public static class Workload {
        @Param({"REST_ONLY", "CROSSING", "MULTI_FILL"})
        public String scenario;
        List<OrderCommand> commands;

        @Setup(Level.Trial)
        public void setup() {
            commands = DeterministicWorkload.commands(
                    DeterministicWorkload.Scenario.valueOf(scenario), DeterministicWorkload.BATCH_SIZE);
        }
    }

    @Benchmark
    @OperationsPerInvocation(DeterministicWorkload.BATCH_SIZE)
    public long batch(Workload workload) {
        long checksum = 0;
        try (var writer = new SingleWriterSequencer(DeterministicWorkload.DEMO, 512, 2_000, 5_000)) {
            for (OrderCommand command : workload.commands) {
                var result = writer.submit(command).completion().toCompletableFuture().join();
                if (!result.successful()) throw new IllegalStateException("workload was rejected");
                checksum += result.sequence();
            }
        }
        return checksum;
    }
}
