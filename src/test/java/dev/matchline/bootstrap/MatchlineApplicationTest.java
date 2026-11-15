package dev.matchline.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MatchlineApplicationTest {

    @Test
    void selfCheckReportsInMemoryMatchingAvailability() {
        Result result = invoke("--self-check");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("MATCHLINE BOOTSTRAP OK", "matching=true http=false journal=true");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void defaultInvocationExplainsScope() {
        Result result = invoke();

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("--self-check", "Java API only");
    }

    @Test
    void versionIsReproducible() {
        Result result = invoke("--version");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("MATCHLINE 0.1.0-SNAPSHOT" + System.lineSeparator());
    }

    @Test
    void invalidArgumentDoesNotReportSuccessfulStartup() {
        Result result = invoke("--place-order");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Unknown argument", "--help");
    }

    @Test
    void extraArgumentsAreRejected() {
        Result result = invoke("--self-check", "--version");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("at most one argument");
    }

    private static Result invoke(String... args) {
        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
        try (PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8)) {
            int code = MatchlineApplication.run(args, stdout, stderr);
            return new Result(code, stdoutBytes.toString(StandardCharsets.UTF_8),
                    stderrBytes.toString(StandardCharsets.UTF_8));
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
