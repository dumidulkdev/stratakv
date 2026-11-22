package io.stratakv;

import io.stratakv.config.EngineConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FlushTest {

    @Test
    void backgroundFlushCreatesSstable(@TempDir Path dir) throws Exception {
        EngineConfig config = EngineConfig.builder(dir)
                .memtableThresholdBytes(128)
                .build();
        try (StrataKvEngine engine = StrataKvEngine.open(config)) {
            for (int i = 0; i < 50; i++) {
                engine.put(("k" + i).getBytes(), new byte[8]);
            }
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
                try {
                    return engine.stats().sstableCount() >= 1;
                } catch (Exception e) {
                    return false;
                }
            });
            assertThat(engine.get("k1".getBytes())).isNotNull();
            assertThat(Files.list(dir.resolve("sst")).count()).isGreaterThanOrEqualTo(1);
        }
    }
}