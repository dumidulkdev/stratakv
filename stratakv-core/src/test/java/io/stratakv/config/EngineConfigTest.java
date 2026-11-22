package io.stratakv.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EngineConfigTest {

    @Test
    void builderDefaults(@TempDir Path dir) {
        EngineConfig config = EngineConfig.builder(dir).build();
        assertThat(config.dataDir()).isEqualTo(dir);
        assertThat(config.memtableThresholdBytes()).isEqualTo(64 * 1024);
        assertThat(config.walSyncMode()).isEqualTo(EngineConfig.WalSyncMode.EVERY_WRITE);
        assertThat(config.groupCommitInterval()).isEqualTo(Duration.ofMillis(5));
        assertThat(config.bloomFalsePositiveRate()).isEqualTo(0.01);
        assertThat(config.sstableBlockSizeBytes()).isEqualTo(4096);
    }

    @Test
    void builderOverrides(@TempDir Path dir) {
        EngineConfig config = EngineConfig.builder(dir)
                .memtableThresholdBytes(128)
                .walSyncMode(EngineConfig.WalSyncMode.GROUP_COMMIT)
                .groupCommitInterval(Duration.ofMillis(10))
                .bloomFalsePositiveRate(0.05)
                .build();
        assertThat(config.memtableThresholdBytes()).isEqualTo(128);
        assertThat(config.walSyncMode()).isEqualTo(EngineConfig.WalSyncMode.GROUP_COMMIT);
        assertThat(config.bloomFalsePositiveRate()).isEqualTo(0.05);
    }
}