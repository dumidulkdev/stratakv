package io.stratakv.manifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class Manifest {

    public record ManifestState(long version, List<String> sstableFiles, String activeWalSegment, long nextSstableId) {}

    private final Path manifestPath;
    private ManifestState state;

    public Manifest(Path dataDir) throws IOException {
        this.manifestPath = dataDir.resolve("MANIFEST");
        if (Files.exists(manifestPath)) {
            this.state = parse(Files.readString(manifestPath));
        } else {
            this.state = new ManifestState(1, new ArrayList<>(), null, 1);
            persist();
        }
    }

    public synchronized ManifestState state() {
        return state;
    }

    public synchronized void addSstable(String name) throws IOException {
        List<String> files = new ArrayList<>(state.sstableFiles());
        files.add(name);
        state = new ManifestState(state.version() + 1, files, state.activeWalSegment(), state.nextSstableId());
        persist();
    }

    public synchronized void replaceSstables(List<String> removed, List<String> added) throws IOException {
        List<String> files = new ArrayList<>(state.sstableFiles());
        files.removeAll(removed);
        files.addAll(added);
        state = new ManifestState(state.version() + 1, files, state.activeWalSegment(), state.nextSstableId() + added.size());
        persist();
    }

    public synchronized void setActiveWal(String walName) throws IOException {
        state = new ManifestState(state.version() + 1, state.sstableFiles(), walName, state.nextSstableId());
        persist();
    }

    public synchronized long allocateSstableId() throws IOException {
        long id = state.nextSstableId();
        state = new ManifestState(state.version() + 1, state.sstableFiles(), state.activeWalSegment(), id + 1);
        persist();
        return id;
    }

    private void persist() throws IOException {
        Path temp = manifestPath.resolveSibling("MANIFEST.tmp");
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(temp, format(state), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temp, manifestPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, manifestPath, StandardCopyOption.REPLACE_EXISTING);
        }
        try (var channel = java.nio.channels.FileChannel.open(manifestPath, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    static String format(ManifestState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("VERSION=").append(state.version()).append('\n');
        sb.append("NEXT_SSTABLE_ID=").append(state.nextSstableId()).append('\n');
        if (state.activeWalSegment() != null) {
            sb.append("ACTIVE_WAL=").append(state.activeWalSegment()).append('\n');
        }
        for (String file : state.sstableFiles()) {
            sb.append("SSTABLE=").append(file).append('\n');
        }
        return sb.toString();
    }

    static ManifestState parse(String content) {
        long version = 1;
        long nextId = 1;
        String activeWal = null;
        List<String> sstables = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case "VERSION" -> version = Long.parseLong(value);
                case "NEXT_SSTABLE_ID" -> nextId = Long.parseLong(value);
                case "ACTIVE_WAL" -> activeWal = value;
                case "SSTABLE" -> sstables.add(value);
                default -> {
                }
            }
        }
        return new ManifestState(version, sstables, activeWal, nextId);
    }
}