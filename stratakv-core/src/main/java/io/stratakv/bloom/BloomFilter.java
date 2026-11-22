package io.stratakv.bloom;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public final class BloomFilter {

    private final byte[] bits;
    private final int numHashes;
    private final int bitCount;

    private BloomFilter(byte[] bits, int numHashes) {
        this.bits = bits;
        this.numHashes = numHashes;
        this.bitCount = bits.length * 8;
    }

    public static BloomFilter create(double falsePositiveRate, int expectedItems) {
        int bitCount = optimalBitCount(expectedItems, falsePositiveRate);
        int byteCount = (bitCount + 7) / 8;
        int numHashes = optimalHashCount(bitCount, expectedItems);
        return new BloomFilter(new byte[byteCount], numHashes);
    }

    public void add(byte[] key) {
        long hash1 = hash(key, 0);
        long hash2 = hash(key, hash1);
        for (int i = 0; i < numHashes; i++) {
            int combined = (int) Math.floorMod(hash1 + (long) i * hash2, bitCount);
            setBit(combined);
        }
    }

    public boolean mightContain(byte[] key) {
        long hash1 = hash(key, 0);
        long hash2 = hash(key, hash1);
        for (int i = 0; i < numHashes; i++) {
            int combined = (int) Math.floorMod(hash1 + (long) i * hash2, bitCount);
            if (!getBit(combined)) {
                return false;
            }
        }
        return true;
    }

    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + bits.length);
        buf.putInt(numHashes);
        buf.putInt(bits.length);
        buf.put(bits);
        return buf.array();
    }

    public static BloomFilter deserialize(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int numHashes = buf.getInt();
        int byteLen = buf.getInt();
        byte[] bits = new byte[byteLen];
        buf.get(bits);
        return new BloomFilter(bits, numHashes);
    }

    public static double measureFalsePositiveRate(BloomFilter filter, Collection<byte[]> absentKeys) {
        if (absentKeys.isEmpty()) {
            return 0.0;
        }
        long falsePositives = absentKeys.stream().filter(filter::mightContain).count();
        return (double) falsePositives / absentKeys.size();
    }

    private void setBit(int index) {
        bits[index / 8] |= (byte) (1 << (index % 8));
    }

    private boolean getBit(int index) {
        return (bits[index / 8] & (1 << (index % 8))) != 0;
    }

    private static long hash(byte[] key, long seed) {
        long h = seed;
        for (byte b : key) {
            h = 31 * h + (b & 0xFF);
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h;
    }

    private static int optimalBitCount(int n, double p) {
        return Math.max(64, (int) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2))));
    }

    private static int optimalHashCount(int m, int n) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    public int bitSizeBytes() {
        return bits.length;
    }

    public int hashFunctions() {
        return numHashes;
    }
}