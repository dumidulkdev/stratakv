package io.stratakv.memtable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class MemTableManager {

    private final long thresholdBytes;
    private final AtomicLong sequence = new AtomicLong(1);
    private MemTable active;
    private final List<MemTable> immutable = new ArrayList<>();

    public MemTableManager(long thresholdBytes) {
        this.thresholdBytes = thresholdBytes;
        this.active = new MemTable(sequence.get());
    }

    public synchronized MemTable active() {
        return active;
    }

    public synchronized List<MemTable> immutableMemtables() {
        return List.copyOf(immutable);
    }

    public synchronized void put(byte[] key, byte[] value, boolean tombstone) {
        long seq = sequence.incrementAndGet();
        active.put(key, value, seq, tombstone);
    }

    public long nextSequence() {
        return sequence.incrementAndGet();
    }

    public synchronized boolean shouldFlush() {
        return active.approximateSizeBytes() >= thresholdBytes;
    }

    public synchronized MemTable rotate() {
        active.makeImmutable();
        immutable.add(active);
        active = new MemTable(sequence.get());
        return immutable.getLast();
    }

    public synchronized void removeImmutable(MemTable table) {
        immutable.remove(table);
    }

    public long thresholdBytes() {
        return thresholdBytes;
    }
}