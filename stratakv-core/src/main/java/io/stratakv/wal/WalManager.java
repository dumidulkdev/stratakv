package io.stratakv.wal;

import io.stratakv.config.EngineConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public final class WalManager implements AutoCloseable {

    private static final String SEGMENT_PREFIX = "wal-";
    private static final String SEGMENT_SUFFIX = ".log";

    private final Path walDir;
    private final EngineConfig.WalSyncMode syncMode;
    private final long groupCommitIntervalMs;
    private final AtomicLong nextSegmentId = new AtomicLong(1);
    private WalSegment current;

    public WalManager(Path dataDir, EngineConfig config) throws IOException {
        this.walDir = dataDir.resolve("wal");
        Files.createDirectories(walDir);
        this.syncMode = config.walSyncMode();
        this.groupCommitIntervalMs = config.groupCommitInterval().toMillis();
        recoverSegmentId();
        roll();
    }

    private void recoverSegmentId() throws IOException {
        long max = 0;
        try (Stream<Path> paths = Files.list(walDir)) {
            for (Path p : paths.filter(f -> f.getFileName().toString().startsWith(SEGMENT_PREFIX)).toList()) {
                String name = p.getFileName().toString();
                String num = name.substring(SEGMENT_PREFIX.length(), name.length() - SEGMENT_SUFFIX.length());
                max = Math.max(max, Long.parseLong(num));
            }
        }
        nextSegmentId.set(max + 1);
    }

    public synchronized void append(WalRecord record) throws IOException {
        current.append(record);
    }

    public synchronized void sync() throws IOException {
        current.sync();
    }

    public synchronized WalSegment roll() throws IOException {
        if (current != null) {
            current.sync();
            current.close();
        }
        long id = nextSegmentId.getAndIncrement();
        Path path = walDir.resolve(SEGMENT_PREFIX + id + SEGMENT_SUFFIX);
        current = new WalSegment(path, syncMode, groupCommitIntervalMs);
        return current;
    }

    public synchronized void deleteSegment(Path path) throws IOException {
        if (current != null && current.path().equals(path)) {
            throw new IllegalStateException("Cannot delete active WAL segment");
        }
        Files.deleteIfExists(path);
    }

    public List<Path> listSegments() throws IOException {
        List<Path> segments = new ArrayList<>();
        try (Stream<Path> paths = Files.list(walDir)) {
            paths.filter(p -> p.getFileName().toString().startsWith(SEGMENT_PREFIX))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(segments::add);
        }
        return segments;
    }

    public WalSegment currentSegment() {
        return current;
    }

    @Override
    public void close() throws IOException {
        if (current != null) {
            current.close();
        }
    }
}