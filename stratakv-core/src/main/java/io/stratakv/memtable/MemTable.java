package io.stratakv.memtable;

import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MemTable {

    private final NavigableMap<InternalKey, byte[]> map = new ConcurrentSkipListMap<>();
    private final AtomicLong approximateBytes = new AtomicLong();
    private final long sequence;
    private volatile boolean immutable;

    public MemTable(long sequence) {
        this.sequence = sequence;
    }

    public void put(byte[] key, byte[] value, long seq, boolean tombstone) {
        if (immutable) {
            throw new IllegalStateException("MemTable is immutable");
        }
        InternalKey ikey = new InternalKey(key, seq, tombstone);
        map.put(ikey, value != null ? value : new byte[0]);
        approximateBytes.addAndGet(key.length + (value != null ? value.length : 0) + 32);
    }

    public byte[] get(byte[] userKey) {
        Map.Entry<InternalKey, byte[]> latest = latestEntry(userKey);
        if (latest == null || latest.getKey().tombstone()) {
            return null;
        }
        return latest.getValue();
    }

    public boolean isDeleted(byte[] userKey) {
        Map.Entry<InternalKey, byte[]> latest = latestEntry(userKey);
        return latest != null && latest.getKey().tombstone();
    }

    private Map.Entry<InternalKey, byte[]> latestEntry(byte[] userKey) {
        Map.Entry<InternalKey, byte[]> best = null;
        for (Map.Entry<InternalKey, byte[]> entry : map.entrySet()) {
            if (!java.util.Arrays.equals(entry.getKey().userKey(), userKey)) {
                continue;
            }
            if (best == null || entry.getKey().sequence() > best.getKey().sequence()) {
                best = entry;
            }
        }
        return best;
    }

    public long approximateSizeBytes() {
        return approximateBytes.get();
    }

    public void makeImmutable() {
        immutable = true;
    }

    public boolean isImmutable() {
        return immutable;
    }

    public Iterator<Map.Entry<InternalKey, byte[]>> iterator() {
        return map.entrySet().iterator();
    }

    public NavigableMap<InternalKey, byte[]> snapshot() {
        return map;
    }

    public long sequence() {
        return sequence;
    }
}