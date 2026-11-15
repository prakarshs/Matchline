package dev.matchline.recovery;

import dev.matchline.bootstrap.MatchlineApplication;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryDrillTest {
    @Test
    void portableRecoveryDrillChecksFullAndSuffixEquivalence() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            assertEquals(0, MatchlineApplication.run(new String[]{"--recovery-demo"}, out, err));
        }
        assertTrue(output.toString(StandardCharsets.UTF_8)
                .contains("MATCHLINE RECOVERY OK | commands=6 snapshot=4 suffix=2 full=6 state_equal=true"));
        assertTrue(errors.toString(StandardCharsets.UTF_8).isEmpty());
    }
}
