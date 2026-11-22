package io.stratakv.sstable;

import io.stratakv.bloom.BloomFilter;
import io.stratakv.memtable.InternalKey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class SsTableReader implements AutoCloseable {

    public record Footer(long indexOffset, long indexLength, long bloomOffset, long bloomLength, int version, long magic) {}

    private final Path path;
    private final FileChannel channel;
    private final Footer footer;
    private final BloomFilter bloom;
    private final List<SsTableWriter.IndexEntry> index;
    private final long fileSize;

    public SsTableReader(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.fileSize = channel.size();
        this.footer = readFooter();
        if (footer.magic() != SsTableConstants.MAGIC) {
            throw new SsTableCorruptionException("Invalid SSTable magic in " + path);
        }
        if (footer.version() != SsTableConstants.VERSION) {
            throw new SsTableCorruptionException("Unsupported SSTable version");
        }
        this.bloom = readBloom();
        this.index = readIndex();
    }

    private Footer readFooter() throws IOException {
        if (fileSize < SsTableWriter.FOOTER_SIZE) {
            throw new SsTableCorruptionException("File too small for footer");
        }
        ByteBuffer buf = ByteBuffer.allocate(SsTableWriter.FOOTER_SIZE);
        channel.position(fileSize - SsTableWriter.FOOTER_SIZE);
        channel.read(buf);
        buf.flip();
        return new Footer(
                buf.getLong(),
                buf.getLong(),
                buf.getLong(),
                buf.getLong(),
                buf.getInt(),
                buf.getLong()
        );
    }

    private BloomFilter readBloom() throws IOException {
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        channel.position(footer.bloomOffset());
        channel.read(lenBuf);
        lenBuf.flip();
        int len = lenBuf.getInt();
        ByteBuffer data = ByteBuffer.allocate(len);
        channel.position(footer.bloomOffset() + 4);
        channel.read(data);
        return BloomFilter.deserialize(data.array());
    }

    private List<SsTableWriter.IndexEntry> readIndex() throws IOException {
        List<SsTableWriter.IndexEntry> entries = new ArrayList<>();
        long pos = footer.indexOffset();
        long end = footer.indexOffset() + footer.indexLength();
        while (pos < end) {
            ByteBuffer keyLenBuf = ByteBuffer.allocate(4);
            channel.position(pos);
            channel.read(keyLenBuf);
            keyLenBuf.flip();
            int keyLen = keyLenBuf.getInt();
            pos += 4;
            ByteBuffer keyBuf = ByteBuffer.allocate(keyLen + 8);
            channel.position(pos);
            channel.read(keyBuf);
            keyBuf.flip();
            byte[] key = new byte[keyLen];
            keyBuf.get(key);
            long blockOffset = keyBuf.getLong();
            entries.add(new SsTableWriter.IndexEntry(key, blockOffset));
            pos += keyLen + 8;
        }
        return entries;
    }

    public boolean mightContain(byte[] userKey) {
        return bloom.mightContain(userKey);
    }

    public LookupResult lookup(byte[] userKey) throws IOException {
        if (!mightContain(userKey)) {
            return LookupResult.miss();
        }
        int blockIdx = findBlock(userKey);
        return scanBlock(blockIdx, userKey);
    }

    public enum LookupKind { MISS, TOMBSTONE, VALUE }

    public record LookupResult(LookupKind kind, byte[] value) {
        public static LookupResult miss() {
            return new LookupResult(LookupKind.MISS, null);
        }
    }

    private int findBlock(byte[] userKey) {
        int lo = 0;
        int hi = index.size() - 1;
        int result = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Arrays.compareUnsigned(index.get(mid).firstKey(), userKey) <= 0) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    private LookupResult scanBlock(int blockIdx, byte[] userKey) throws IOException {
        long start = index.get(blockIdx).blockOffset();
        long end = blockIdx + 1 < index.size() ? index.get(blockIdx + 1).blockOffset() : footer.indexOffset();
        ByteBuffer block = ByteBuffer.allocate((int) (end - start));
        channel.position(start);
        channel.read(block);
        block.flip();
        byte[] best = null;
        long bestSeq = -1;
        boolean tombstone = false;
        while (block.hasRemaining()) {
            int keyLen = block.getInt();
            byte[] encodedKey = new byte[keyLen];
            block.get(encodedKey);
            int valLen = block.getInt();
            byte[] value = new byte[valLen];
            if (valLen > 0) {
                block.get(value);
            }
            InternalKey key = decodeKey(encodedKey);
            if (!Arrays.equals(key.userKey(), userKey)) {
                continue;
            }
            if (key.sequence() > bestSeq) {
                bestSeq = key.sequence();
                tombstone = key.tombstone();
                best = value;
            }
        }
        if (bestSeq < 0) {
            return LookupResult.miss();
        }
        if (tombstone) {
            return new LookupResult(LookupKind.TOMBSTONE, null);
        }
        return new LookupResult(LookupKind.VALUE, best);
    }

    static InternalKey decodeKey(byte[] encoded) {
        int userKeyLen = encoded.length - 9;
        byte[] userKey = Arrays.copyOfRange(encoded, 0, userKeyLen);
        long seq = 0;
        for (int i = 0; i < 8; i++) {
            seq = (seq << 8) | (encoded[userKeyLen + i] & 0xFF);
        }
        boolean tomb = encoded[encoded.length - 1] == 1;
        return new InternalKey(userKey, seq, tomb);
    }

    public List<MapLikeEntry> scanAll() throws IOException {
        List<MapLikeEntry> out = new ArrayList<>();
        for (int i = 0; i < index.size(); i++) {
            long start = index.get(i).blockOffset();
            long end = i + 1 < index.size() ? index.get(i + 1).blockOffset() : footer.indexOffset();
            ByteBuffer block = ByteBuffer.allocate((int) (end - start));
            channel.position(start);
            channel.read(block);
            block.flip();
            while (block.hasRemaining()) {
                int keyLen = block.getInt();
                byte[] encodedKey = new byte[keyLen];
                block.get(encodedKey);
                int valLen = block.getInt();
                byte[] value = new byte[valLen];
                if (valLen > 0) {
                    block.get(value);
                }
                InternalKey key = decodeKey(encodedKey);
                out.add(new MapLikeEntry(key, value));
            }
        }
        return out;
    }

    public record MapLikeEntry(InternalKey key, byte[] value) {}

    public Path path() {
        return path;
    }

    public long fileSizeBytes() {
        return fileSize;
    }

    public BloomFilter bloomFilter() {
        return bloom;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}