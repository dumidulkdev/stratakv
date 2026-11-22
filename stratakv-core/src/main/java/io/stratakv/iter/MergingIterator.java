package io.stratakv.iter;

import io.stratakv.memtable.InternalKey;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

public final class MergingIterator implements Iterator<MergingIterator.KeyValue> {

    public record KeyValue(InternalKey key, byte[] value) {}

    private final PriorityQueue<HeapEntry> heap;

    public MergingIterator(List<Iterator<KeyValue>> sources) {
        this.heap = new PriorityQueue<>(Comparator
                .comparing((HeapEntry e) -> e.next.key, InternalKey.USER_KEY_ORDER)
                .thenComparingInt(e -> e.sourceIndex));
        for (int i = 0; i < sources.size(); i++) {
            Iterator<KeyValue> it = sources.get(i);
            if (it.hasNext()) {
                heap.add(new HeapEntry(i, sources, it.next()));
            }
        }
    }

    @Override
    public boolean hasNext() {
        return !heap.isEmpty();
    }

    @Override
    public KeyValue next() {
        HeapEntry top = heap.poll();
        KeyValue current = top.next;
        byte[] userKey = current.key().userKey();

        while (!heap.isEmpty() && Arrays.equals(heap.peek().next.key().userKey(), userKey)) {
            heap.poll();
        }

        if (top.iterator.hasNext()) {
            heap.add(new HeapEntry(top.sourceIndex, top.sources, top.iterator.next()));
        }
        return current;
    }

    private static final class HeapEntry {
        private final int sourceIndex;
        private final List<Iterator<KeyValue>> sources;
        private final Iterator<KeyValue> iterator;
        private final KeyValue next;

        private HeapEntry(int sourceIndex, List<Iterator<KeyValue>> sources, KeyValue next) {
            this.sourceIndex = sourceIndex;
            this.sources = sources;
            this.iterator = sources.get(sourceIndex);
            this.next = next;
        }
    }
}