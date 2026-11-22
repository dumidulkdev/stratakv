package io.stratakv;

import io.stratakv.config.EngineConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionTest {

    @Test
    void compactionMergesSstables(@TempDir Path dir) throws Exception {
        EngineConfig config = EngineConfig.builder(dir)
                .memtableThresholdBytes(80)
                .compactionTierCount(2)
                .build();
        try (StrataKvEngine engine = StrataKvEngine.open(config)) {
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < 40; i++) {
                    engine.put(("k" + round + "-" + i).getBytes(), new byte[12]);
                }
                Thread.sleep(150);
            }
            Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> engine.stats().sstableCount() <= 4);
            EngineStats before = engine.stats();
            engine.compact();
            EngineStats after = engine.stats();
            assertThat(after.sstableCount()).isLessThanOrEqualTo(before.sstableCount());
            assertThat(after.compactionCount()).isGreaterThan(0);
            assertThat(engine.get("k1-1".getBytes())).isNotNull();
        }
    }
}