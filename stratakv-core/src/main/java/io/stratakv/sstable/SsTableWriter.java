package io.stratakv.sstable;

import io.stratakv.bloom.BloomFilter;
import io.stratakv.memtable.InternalKey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SsTableWriter implements AutoCloseable {

    public record IndexEntry(byte[] firstKey, long blockOffset) {}

    public static final int FOOTER_SIZE = 44;

    private final Path path;
    private final int blockSize;
    private final BloomFilter bloom;
    private final FileChannel channel;
    private final List<IndexEntry> sparseIndex = new ArrayList<>();
    private final ByteBuffer blockBuffer;
    private long fileOffset;
    private byte[] firstKeyInBlock;

    public SsTableWriter(Path path, int blockSize, double bloomFpr, int expectedKeys) throws IOException {
        this.path = path;
        this.blockSize = blockSize;
        this.bloom = BloomFilter.create(bloomFpr, Math.max(1, expectedKeys));
        Files.createDirectories(path.getParent());
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        this.blockBuffer = ByteBuffer.allocate(blockSize + 65536);
        this.fileOffset = 0;
    }

    public void add(InternalKey key, byte[] value) throws IOException {
        bloom.add(key.userKey());
        byte[] record = encodeRecord(key, value);
        if (blockBuffer.position() + record.length > blockSize && blockBuffer.position() > 0) {
            flushBlock();
        }
        if (firstKeyInBlock == null) {
            firstKeyInBlock = key.userKey().clone();
        }
        blockBuffer.put(record);
    }

    static byte[] encodeRecord(InternalKey key, byte[] value) {
        byte[] encodedKey = key.encode();
        ByteBuffer buf = ByteBuffer.allocate(4 + encodedKey.length + 4 + value.length);
        buf.putInt(encodedKey.length);
        buf.put(encodedKey);
        buf.putInt(value.length);
        buf.put(value);
        return buf.array();
    }

    private void flushBlock() throws IOException {
        blockBuffer.flip();
        sparseIndex.add(new IndexEntry(firstKeyInBlock, fileOffset));
        channel.write(blockBuffer);
        fileOffset += blockBuffer.limit();
        blockBuffer.clear();
        firstKeyInBlock = null;
    }

    public void finish() throws IOException {
        if (blockBuffer.position() > 0) {
            flushBlock();
        }
        long dataEnd = fileOffset;

        long indexOffset = fileOffset;
        long indexLength = writeIndex();
        long bloomOffset = fileOffset;
        long bloomLength = writeBloom();
        writeFooter(indexOffset, indexLength, bloomOffset, bloomLength);
        channel.force(true);
    }

    private long writeIndex() throws IOException {
        long start = fileOffset;
        for (IndexEntry entry : sparseIndex) {
            ByteBuffer buf = ByteBuffer.allocate(4 + entry.firstKey().length + 8);
            buf.putInt(entry.firstKey().length);
            buf.put(entry.firstKey());
            buf.putLong(entry.blockOffset());
            buf.flip();
            channel.write(buf);
            fileOffset += buf.limit();
        }
        return fileOffset - start;
    }

    private long writeBloom() throws IOException {
        long start = fileOffset;
        byte[] data = bloom.serialize();
        ByteBuffer buf = ByteBuffer.allocate(4 + data.length);
        buf.putInt(data.length);
        buf.put(data);
        buf.flip();
        channel.write(buf);
        fileOffset += buf.limit();
        return fileOffset - start;
    }

    private void writeFooter(long indexOffset, long indexLength, long bloomOffset, long bloomLength) throws IOException {
        ByteBuffer footer = ByteBuffer.allocate(FOOTER_SIZE);
        footer.putLong(indexOffset);
        footer.putLong(indexLength);
        footer.putLong(bloomOffset);
        footer.putLong(bloomLength);
        footer.putInt(SsTableConstants.VERSION);
        footer.putLong(SsTableConstants.MAGIC);
        footer.flip();
        channel.write(footer);
        fileOffset += footer.limit();
    }

    public static void writeFromMemtable(Path path, int blockSize, double bloomFpr,
                                         Iterable<Map.Entry<InternalKey, byte[]>> entries) throws IOException {
        List<Map.Entry<InternalKey, byte[]>> list = new ArrayList<>();
        for (Map.Entry<InternalKey, byte[]> e : entries) {
            list.add(e);
        }
        try (SsTableWriter writer = new SsTableWriter(path, blockSize, bloomFpr, list.size())) {
            for (Map.Entry<InternalKey, byte[]> e : list) {
                writer.add(e.getKey(), e.getValue());
            }
            writer.finish();
        }
    }

    public BloomFilter bloomFilter() {
        return bloom;
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}