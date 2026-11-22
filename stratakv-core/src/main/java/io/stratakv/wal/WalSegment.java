package io.stratakv.wal;

import io.stratakv.config.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

public final class WalSegment implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WalSegment.class);
    public static final int HEADER_BYTES = 4 + 4 + 4;

    private final Path path;
    private final FileChannel channel;
    private final EngineConfig.WalSyncMode syncMode;
    private final long groupCommitNanos;
    private final AtomicLong lastFsyncNanos = new AtomicLong(System.nanoTime());

    public WalSegment(Path path, EngineConfig.WalSyncMode syncMode, long groupCommitIntervalMs) throws IOException {
        this.path = path;
        this.syncMode = syncMode;
        this.groupCommitNanos = groupCommitIntervalMs * 1_000_000L;
        Files.createDirectories(path.getParent());
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ);
    }

    public static WalSegment openExisting(Path path, EngineConfig.WalSyncMode syncMode, long groupCommitIntervalMs) throws IOException {
        WalSegment segment = new WalSegment(path, syncMode, groupCommitIntervalMs);
        segment.channel.position(segment.channel.size());
        return segment;
    }

    public Path path() {
        return path;
    }

    public synchronized void append(WalRecord record) throws IOException {
        byte[] payload = record.payload();
        CRC32 crc = new CRC32();
        crc.update(payload);
        int checksum = (int) crc.getValue();

        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(payload.length);
        header.putInt(checksum);
        header.putInt(payload.length);
        header.flip();

        channel.write(header);
        channel.write(ByteBuffer.wrap(payload));
        maybeFsync();
    }

    private void maybeFsync() throws IOException {
        if (syncMode == EngineConfig.WalSyncMode.EVERY_WRITE) {
            channel.force(true);
            return;
        }
        long now = System.nanoTime();
        if (now - lastFsyncNanos.get() >= groupCommitNanos) {
            channel.force(true);
            lastFsyncNanos.set(now);
        }
    }

    public synchronized void sync() throws IOException {
        channel.force(true);
        lastFsyncNanos.set(System.nanoTime());
    }

    public List<WalRecord> readAll() throws IOException {
        return readAll(false);
    }

    public static List<WalRecord> readAll(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            return readChannel(ch, false);
        }
    }

    public List<WalRecord> readAll(boolean truncateTornTail) throws IOException {
        channel.position(0);
        return readChannel(channel, truncateTornTail);
    }

    private static List<WalRecord> readChannel(FileChannel channel, boolean truncateTornTail) throws IOException {
        List<WalRecord> records = new ArrayList<>();
        long fileSize = channel.size();
        long pos = 0;
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);

        while (pos + HEADER_BYTES <= fileSize) {
            header.clear();
            channel.position(pos);
            int read = channel.read(header);
            if (read < HEADER_BYTES) {
                break;
            }
            header.flip();
            int length = header.getInt();
            int expectedCrc = header.getInt();
            int lengthDup = header.getInt();
            if (length != lengthDup || length < 0 || length > 16 * 1024 * 1024) {
                if (truncateTornTail) {
                    truncateFile(channel, pos);
                }
                break;
            }
            long recordEnd = pos + HEADER_BYTES + length;
            if (recordEnd > fileSize) {
                if (truncateTornTail) {
                    truncateFile(channel, pos);
                }
                break;
            }
            ByteBuffer payload = ByteBuffer.allocate(length);
            channel.position(pos + HEADER_BYTES);
            channel.read(payload);
            payload.flip();

            CRC32 crc = new CRC32();
            crc.update(payload);
            if ((int) crc.getValue() != expectedCrc) {
                throw new WalCorruptionException("Checksum mismatch at offset " + pos);
            }
            records.add(WalRecord.decode(payload.array()));
            pos = recordEnd;
        }
        return records;
    }

    private static void truncateFile(FileChannel channel, long newSize) throws IOException {
        log.warn("Truncating torn WAL tail at offset {}", newSize);
        channel.truncate(newSize);
    }

    @Override
    public void close() throws IOException {
        sync();
        channel.close();
    }
}