package io.stratakv;

import io.stratakv.config.EngineConfig;
import io.stratakv.iter.MergingIterator;
import io.stratakv.manifest.Manifest;
import io.stratakv.memtable.InternalKey;
import io.stratakv.memtable.MemTable;
import io.stratakv.memtable.MemTableManager;
import io.stratakv.sstable.SsTableReader;
import io.stratakv.sstable.SsTableWriter;
import io.stratakv.wal.WalManager;
import io.stratakv.wal.WalRecord;
import io.stratakv.wal.WalRecordType;
import io.stratakv.wal.WalSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

public final class StrataKvEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StrataKvEngine.class);

    private final EngineConfig config;
    private final Path dataDir;
    private final WalManager walManager;
    private final MemTableManager memTableManager;
    private final Manifest manifest;
    private final List<SsTableHandle> sstables = new ArrayList<>();
    private final ExecutorService background;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong flushCount = new AtomicLong();
    private final AtomicLong compactionCount = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong userBytesWritten = new AtomicLong();
    private volatile boolean closed;

    public StrataKvEngine(EngineConfig config) throws IOException {
        this.config = config;
        this.dataDir = config.dataDir();
        Files.createDirectories(dataDir);
        cleanupOrphans();
        this.manifest = new Manifest(dataDir);
        this.walManager = new WalManager(dataDir, config);
        this.memTableManager = new MemTableManager(config.memtableThresholdBytes());
        this.background = Executors.newVirtualThreadPerTaskExecutor();
        loadSstables();
        recoverFromWal();
        manifest.setActiveWal(walManager.currentSegment().path().getFileName().toString());
    }

    public static StrataKvEngine open(EngineConfig config) throws IOException {
        return new StrataKvEngine(config);
    }

    public void put(byte[] key, byte[] value) throws IOException {
        ensureOpen();
        lock.writeLock().lock();
        try {
            walManager.append(new WalRecord(WalRecordType.PUT, key, value));
            walManager.sync();
            memTableManager.put(key, value, false);
            userBytesWritten.addAndGet(key.length + value.length);
            maybeScheduleFlush();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(byte[] key) throws IOException {
        ensureOpen();
        lock.writeLock().lock();
        try {
            walManager.append(new WalRecord(WalRecordType.DELETE, key, null));
            walManager.sync();
            memTableManager.put(key, null, true);
            userBytesWritten.addAndGet(key.length);
            maybeScheduleFlush();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public byte[] get(byte[] key) throws IOException {
        ensureOpen();
        lock.readLock().lock();
        try {
            return resolveGet(key).orElse(null);
        } finally {
            lock.readLock().unlock();
        }
    }

    private Optional<byte[]> resolveGet(byte[] key) throws IOException {
        byte[] active = memTableManager.active().get(key);
        if (active != null) {
            return Optional.of(active);
        }
        if (memTableManager.active().isDeleted(key)) {
            return Optional.empty();
        }
        for (int i = memTableManager.immutableMemtables().size() - 1; i >= 0; i--) {
            MemTable table = memTableManager.immutableMemtables().get(i);
            if (table.isDeleted(key)) {
                return Optional.empty();
            }
            byte[] value = table.get(key);
            if (value != null) {
                return Optional.of(value);
            }
        }
        for (int i = sstables.size() - 1; i >= 0; i--) {
            SsTableHandle handle = sstables.get(i);
            if (!handle.reader.mightContain(key)) {
                continue;
            }
            SsTableReader.LookupResult result = handle.reader.lookup(key);
            if (result.kind() == SsTableReader.LookupKind.VALUE) {
                return Optional.of(result.value());
            }
            if (result.kind() == SsTableReader.LookupKind.TOMBSTONE) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Iterator<MergingIterator.KeyValue> scan(byte[] startInclusive, byte[] endExclusive) throws IOException {
        lock.readLock().lock();
        try {
            List<Iterator<MergingIterator.KeyValue>> sources = new ArrayList<>();
            sources.add(memtableIterator(memTableManager.active(), startInclusive, endExclusive));
            for (int i = memTableManager.immutableMemtables().size() - 1; i >= 0; i--) {
                sources.add(memtableIterator(memTableManager.immutableMemtables().get(i), startInclusive, endExclusive));
            }
            for (int i = sstables.size() - 1; i >= 0; i--) {
                sources.add(sstableIterator(sstables.get(i), startInclusive, endExclusive));
            }
            return new FilteringIterator(new MergingIterator(sources), startInclusive, endExclusive);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void maybeScheduleFlush() {
        if (memTableManager.shouldFlush()) {
            background.submit(this::flushImmutableMemtables);
        }
    }

    private void flushImmutableMemtables() {
        MemTable toFlush;
        lock.writeLock().lock();
        try {
            if (!memTableManager.shouldFlush()) {
                return;
            }
            toFlush = memTableManager.rotate();
        } finally {
            lock.writeLock().unlock();
        }
        try {
            flushMemTable(toFlush);
        } catch (IOException e) {
            log.error("Flush failed", e);
        }
    }

    private void flushMemTable(MemTable table) throws IOException {
        lock.writeLock().lock();
        try {
            long id = manifest.allocateSstableId();
            String name = String.format("%04d.sst", id);
            Path sstPath = dataDir.resolve("sst").resolve(name);
            SsTableWriter.writeFromMemtable(sstPath, config.sstableBlockSizeBytes(),
                    config.bloomFalsePositiveRate(), table.snapshot().entrySet());
            bytesWritten.addAndGet(Files.size(sstPath));
            manifest.addSstable(name);
            SsTableReader reader = new SsTableReader(sstPath);
            sstables.add(new SsTableHandle(name, sstPath, reader, Files.size(sstPath)));
            memTableManager.removeImmutable(table);
            List<Path> beforeRoll = walManager.listSegments();
            walManager.roll();
            manifest.setActiveWal(walManager.currentSegment().path().getFileName().toString());
            for (Path wal : beforeRoll) {
                if (!wal.equals(walManager.currentSegment().path())) {
                    walManager.deleteSegment(wal);
                }
            }
            flushCount.incrementAndGet();
            maybeCompact();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void compact() throws IOException {
        lock.writeLock().lock();
        try {
            runCompaction();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void maybeCompact() throws IOException {
        if (sstables.size() >= config.compactionTierCount()) {
            runCompaction();
        }
    }

    private void runCompaction() throws IOException {
        if (sstables.size() < 2) {
            return;
        }
        List<SsTableHandle> sorted = new ArrayList<>(sstables);
        sorted.sort(Comparator.comparingLong(SsTableHandle::sizeBytes));
        List<SsTableHandle> tier = new ArrayList<>();
        long base = sorted.get(0).sizeBytes();
        for (SsTableHandle h : sorted) {
            if (h.sizeBytes() <= base * 2) {
                tier.add(h);
            }
        }
        if (tier.size() < 2) {
            tier = sorted.subList(0, Math.min(2, sorted.size()));
        }
        List<MergingIterator.KeyValue> merged = new ArrayList<>();
        List<Iterator<MergingIterator.KeyValue>> iters = new ArrayList<>();
        List<SsTableHandle> mergeOrder = new ArrayList<>(tier);
        java.util.Collections.reverse(mergeOrder);
        for (SsTableHandle handle : mergeOrder) {
            iters.add(sstableIterator(handle, new byte[0], null));
        }
        MergingIterator merge = new MergingIterator(iters);
        byte[] lastUserKey = null;
        while (merge.hasNext()) {
            MergingIterator.KeyValue kv = merge.next();
            if (lastUserKey != null && Arrays.equals(lastUserKey, kv.key().userKey())) {
                continue;
            }
            lastUserKey = kv.key().userKey().clone();
            if (!kv.key().tombstone()) {
                merged.add(kv);
            }
        }
        long newId = manifest.allocateSstableId();
        String newName = String.format("%04d.sst", newId);
        Path newPath = dataDir.resolve("sst").resolve(newName);
        try (SsTableWriter writer = new SsTableWriter(newPath, config.sstableBlockSizeBytes(),
                config.bloomFalsePositiveRate(), merged.size())) {
            for (MergingIterator.KeyValue kv : merged) {
                writer.add(kv.key(), kv.value());
            }
            writer.finish();
        }
        bytesWritten.addAndGet(Files.size(newPath));
        List<String> removed = tier.stream().map(SsTableHandle::name).toList();
        manifest.replaceSstables(removed, List.of(newName));
        for (SsTableHandle handle : tier) {
            handle.reader.close();
            Files.deleteIfExists(handle.path);
            sstables.remove(handle);
        }
        SsTableReader reader = new SsTableReader(newPath);
        sstables.add(new SsTableHandle(newName, newPath, reader, Files.size(newPath)));
        compactionCount.incrementAndGet();
    }

    private void loadSstables() throws IOException {
        Path sstDir = dataDir.resolve("sst");
        Files.createDirectories(sstDir);
        for (String name : manifest.state().sstableFiles()) {
            Path path = sstDir.resolve(name);
            if (Files.exists(path)) {
                SsTableReader reader = new SsTableReader(path);
                sstables.add(new SsTableHandle(name, path, reader, Files.size(path)));
            }
        }
    }

    private void recoverFromWal() throws IOException {
        for (Path wal : walManager.listSegments()) {
            List<WalRecord> records;
            if (wal.equals(walManager.currentSegment().path())) {
                try (WalSegment segment = WalSegment.openExisting(wal, config.walSyncMode(),
                        config.groupCommitInterval().toMillis())) {
                    records = segment.readAll(true);
                }
            } else {
                records = WalSegment.readAll(wal);
            }
            for (WalRecord record : records) {
                if (record.type() == WalRecordType.PUT) {
                    memTableManager.put(record.key(), record.value(), false);
                } else {
                    memTableManager.put(record.key(), null, true);
                }
            }
        }
    }

    private void cleanupOrphans() throws IOException {
        try (Stream<Path> paths = Files.list(dataDir)) {
            paths.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
        Path sstDir = dataDir.resolve("sst");
        if (Files.isDirectory(sstDir)) {
            try (Stream<Path> paths = Files.list(sstDir)) {
                paths.filter(p -> p.getFileName().toString().endsWith(".sst.tmp"))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
    }

    public EngineStats stats() throws IOException {
        lock.readLock().lock();
        try {
            long sstBytes = sstables.stream().mapToLong(SsTableHandle::sizeBytes).sum();
            long imm = memTableManager.immutableMemtables().stream().mapToLong(MemTable::approximateSizeBytes).sum();
            double fpr = measureBloomFpr();
            double writeAmp = userBytesWritten.get() == 0 ? 1.0 : (double) bytesWritten.get() / userBytesWritten.get();
            return new EngineStats(sstables.size(), sstBytes, memTableManager.active().approximateSizeBytes(),
                    imm, fpr, writeAmp, compactionCount.get(), flushCount.get());
        } finally {
            lock.readLock().unlock();
        }
    }

    private double measureBloomFpr() {
        if (sstables.isEmpty()) {
            return 0.0;
        }
        List<byte[]> probes = List.of("probe-miss-1".getBytes(), "probe-miss-2".getBytes(), "probe-miss-3".getBytes());
        long hits = 0;
        for (byte[] key : probes) {
            for (SsTableHandle handle : sstables) {
                if (handle.reader.mightContain(key)) {
                    hits++;
                }
            }
        }
        return (double) hits / (probes.size() * sstables.size());
    }

    public void awaitQuiescence() throws InterruptedException {
        Thread.sleep(200);
        for (int i = 0; i < 50; i++) {
            if (memTableManager.immutableMemtables().isEmpty()) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private Iterator<MergingIterator.KeyValue> memtableIterator(MemTable table, byte[] start, byte[] end) {
        java.util.HashMap<String, MergingIterator.KeyValue> latest = new java.util.HashMap<>();
        for (var entry : table.snapshot().entrySet()) {
            byte[] userKey = entry.getKey().userKey();
            if (compareBytes(userKey, start) < 0) {
                continue;
            }
            if (end != null && compareBytes(userKey, end) >= 0) {
                continue;
            }
            String hex = Arrays.toString(userKey);
            MergingIterator.KeyValue candidate = new MergingIterator.KeyValue(entry.getKey(), entry.getValue());
            MergingIterator.KeyValue existing = latest.get(hex);
            if (existing == null || entry.getKey().sequence() > existing.key().sequence()) {
                latest.put(hex, candidate);
            }
        }
        List<MergingIterator.KeyValue> items = new ArrayList<>(latest.values());
        items.sort(Comparator.comparing(kv -> kv.key(), InternalKey.USER_KEY_ORDER));
        return items.iterator();
    }

    private Iterator<MergingIterator.KeyValue> sstableIterator(SsTableHandle handle, byte[] start, byte[] end) throws IOException {
        java.util.HashMap<String, MergingIterator.KeyValue> latest = new java.util.HashMap<>();
        for (SsTableReader.MapLikeEntry entry : handle.reader.scanAll()) {
            byte[] userKey = entry.key().userKey();
            if (compareBytes(userKey, start) < 0) {
                continue;
            }
            if (end != null && compareBytes(userKey, end) >= 0) {
                continue;
            }
            String hex = Arrays.toString(userKey);
            MergingIterator.KeyValue candidate = new MergingIterator.KeyValue(entry.key(), entry.value());
            MergingIterator.KeyValue existing = latest.get(hex);
            if (existing == null || entry.key().sequence() > existing.key().sequence()) {
                latest.put(hex, candidate);
            }
        }
        List<MergingIterator.KeyValue> items = new ArrayList<>(latest.values());
        items.sort(Comparator.comparing(kv -> kv.key(), InternalKey.USER_KEY_ORDER));
        return items.iterator();
    }

    private static int compareBytes(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Engine is closed");
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        background.shutdown();
        lock.writeLock().lock();
        try {
            for (SsTableHandle handle : sstables) {
                handle.reader.close();
            }
            walManager.close();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private record SsTableHandle(String name, Path path, SsTableReader reader, long sizeBytes) {}

    private static final class FilteringIterator implements Iterator<MergingIterator.KeyValue> {
        private final MergingIterator inner;
        private final byte[] start;
        private final byte[] end;
        private MergingIterator.KeyValue next;

        FilteringIterator(MergingIterator inner, byte[] start, byte[] end) {
            this.inner = inner;
            this.start = start;
            this.end = end;
            advance();
        }

        private void advance() {
            next = null;
            while (inner.hasNext()) {
                MergingIterator.KeyValue kv = inner.next();
                if (compareBytes(kv.key().userKey(), start) < 0) {
                    continue;
                }
                if (end != null && compareBytes(kv.key().userKey(), end) >= 0) {
                    break;
                }
                if (kv.key().tombstone()) {
                    continue;
                }
                next = kv;
                return;
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public MergingIterator.KeyValue next() {
            MergingIterator.KeyValue current = next;
            advance();
            return current;
        }
    }
}