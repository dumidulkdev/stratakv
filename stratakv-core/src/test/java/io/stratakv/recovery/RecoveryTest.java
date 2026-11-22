package io.stratakv.recovery;

import io.stratakv.StrataKvEngine;
import io.stratakv.config.EngineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryTest {

    @Test
    void ignoresOrphanManifestTemp(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("MANIFEST.tmp"), "VERSION=1\nNEXT_SSTABLE_ID=1\n");
        EngineConfig config = EngineConfig.builder(dir).build();
        try (StrataKvEngine engine = RecoveryService.recover(config)) {
            engine.put("k".getBytes(), "v".getBytes());
            assertThat(engine.get("k".getBytes())).isEqualTo("v".getBytes());
            assertThat(Files.exists(dir.resolve("MANIFEST.tmp"))).isFalse();
        }
    }
}