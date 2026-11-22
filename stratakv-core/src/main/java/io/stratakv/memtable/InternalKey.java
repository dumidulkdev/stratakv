package io.stratakv.memtable;

import java.util.Arrays;
import java.util.Comparator;

public record InternalKey(byte[] userKey, long sequence, boolean tombstone) implements Comparable<InternalKey> {

    public static final Comparator<InternalKey> USER_KEY_ORDER =
            (a, b) -> Arrays.compareUnsigned(a.userKey, b.userKey);

    @Override
    public int compareTo(InternalKey other) {
        int cmp = Arrays.compareUnsigned(userKey, other.userKey);
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare(other.sequence, sequence);
    }

    public byte[] encode() {
        byte[] out = new byte[userKey.length + 8 + 1];
        System.arraycopy(userKey, 0, out, 0, userKey.length);
        for (int i = 0; i < 8; i++) {
            out[userKey.length + i] = (byte) (sequence >>> ((7 - i) * 8));
        }
        out[out.length - 1] = tombstone ? (byte) 1 : (byte) 0;
        return out;
    }
}