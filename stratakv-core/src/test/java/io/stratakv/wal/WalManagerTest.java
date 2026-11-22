package io.stratakv.wal;

import io.stratakv.config.EngineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WalManagerTest {

    @Test
    void appendAndRoll(@TempDir Path dir) throws Exception {
        EngineConfig config = EngineConfig.builder(dir).build();
        try (WalManager manager = new WalManager(dir, config)) {
            manager.append(new WalRecord(WalRecordType.PUT, "a".getBytes(), "1".getBytes()));
            Path first = manager.currentSegment().path();
            manager.roll();
            manager.append(new WalRecord(WalRecordType.PUT, "b".getBytes(), "2".getBytes()));
            List<Path> segments = manager.listSegments();
            assertThat(segments).hasSize(2);
            assertThat(WalSegment.readAll(first)).hasSize(1);
            assertThat(WalSegment.readAll(manager.currentSegment().path())).hasSize(1);
        }
    }
}