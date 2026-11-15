package dev.matchline.bootstrap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapStatusTest {

    @Test
    void reportsCurrentInMemoryMatchingButNoHttpOrJournal() {
        BootstrapStatus status = BootstrapStatus.current();

        assertThat(status.application()).isEqualTo("MATCHLINE");
        assertThat(status.version()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(status.matchingAvailable()).isTrue();
        assertThat(status.httpAvailable()).isFalse();
        assertThat(status.journalAvailable()).isFalse();
    }

    @Test
    void javaFeatureGateUsesTwentyOneAsMinimum() {
        assertThat(BootstrapStatus.supportedJavaFeature(20)).isFalse();
        assertThat(BootstrapStatus.supportedJavaFeature(21)).isTrue();
        assertThat(BootstrapStatus.supportedJavaFeature(24)).isTrue();
    }
}
