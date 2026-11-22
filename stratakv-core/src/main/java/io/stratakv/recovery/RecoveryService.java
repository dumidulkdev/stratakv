package io.stratakv.recovery;

import io.stratakv.StrataKvEngine;
import io.stratakv.config.EngineConfig;
import io.stratakv.manifest.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private RecoveryService() {
    }

    public static StrataKvEngine recover(EngineConfig config) throws IOException {
        Path dataDir = config.dataDir();
        deleteOrphanTemps(dataDir);
        Manifest manifest = new Manifest(dataDir);
        log.info("Recovered MANIFEST version {} with {} SSTables",
                manifest.state().version(), manifest.state().sstableFiles().size());
        return StrataKvEngine.open(config);
    }

    static void deleteOrphanTemps(Path dataDir) throws IOException {
        if (!Files.isDirectory(dataDir)) {
            return;
        }
        try (Stream<Path> root = Files.list(dataDir)) {
            root.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(RecoveryService::deleteQuietly);
        }
        Path sstDir = dataDir.resolve("sst");
        if (Files.isDirectory(sstDir)) {
            try (Stream<Path> sst = Files.list(sstDir)) {
                sst.filter(p -> p.getFileName().toString().contains(".tmp")).forEach(RecoveryService::deleteQuietly);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
            log.info("Removed orphan temp file {}", path);
        } catch (IOException e) {
            log.warn("Failed to remove orphan {}", path);
        }
    }
}