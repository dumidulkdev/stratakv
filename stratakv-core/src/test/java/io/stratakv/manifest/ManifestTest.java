package io.stratakv.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestTest {

    @Test
    void atomicPersistAndReload(@TempDir Path dir) throws Exception {
        Manifest manifest = new Manifest(dir);
        manifest.addSstable("0001.sst");
        manifest.setActiveWal("wal-1.log");
        Manifest reloaded = new Manifest(dir);
        assertThat(reloaded.state().sstableFiles()).containsExactly("0001.sst");
        assertThat(reloaded.state().activeWalSegment()).isEqualTo("wal-1.log");
        assertThat(Files.exists(dir.resolve("MANIFEST.tmp"))).isFalse();
    }
}