package io.stratakv.wal;

import io.stratakv.config.EngineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalSegmentTest {

    @Test
    void roundTripPutAndDelete(@TempDir Path dir) throws IOException {
        Path wal = dir.resolve("wal-1.log");
        try (WalSegment segment = new WalSegment(wal, EngineConfig.WalSyncMode.EVERY_WRITE, 5)) {
            segment.append(new WalRecord(WalRecordType.PUT, "key1".getBytes(), "value1".getBytes()));
            segment.append(new WalRecord(WalRecordType.DELETE, "key2".getBytes(), null));
        }
        List<WalRecord> records = WalSegment.readAll(wal);
        assertThat(records).hasSize(2);
        assertThat(new String(records.get(0).key())).isEqualTo("key1");
        assertThat(new String(records.get(0).value())).isEqualTo("value1");
        assertThat(records.get(1).type()).isEqualTo(WalRecordType.DELETE);
    }

    @Test
    void checksumDetectsCorruption(@TempDir Path dir) throws IOException {
        Path wal = dir.resolve("wal-1.log");
        try (WalSegment segment = new WalSegment(wal, EngineConfig.WalSyncMode.EVERY_WRITE, 5)) {
            segment.append(new WalRecord(WalRecordType.PUT, "k".getBytes(), "v".getBytes()));
        }
        try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(1);
            ch.position(5);
            ch.read(buf);
            buf.flip();
            byte b = buf.get();
            buf.clear();
            buf.put((byte) (b ^ 0xFF));
            buf.flip();
            ch.position(5);
            ch.write(buf);
        }
        assertThatThrownBy(() -> WalSegment.readAll(wal))
                .isInstanceOf(WalCorruptionException.class)
                .hasMessageContaining("Checksum");
    }

    @Test
    void tornTailIsTruncated(@TempDir Path dir) throws IOException {
        Path wal = dir.resolve("wal-1.log");
        try (WalSegment segment = new WalSegment(wal, EngineConfig.WalSyncMode.EVERY_WRITE, 5)) {
            segment.append(new WalRecord(WalRecordType.PUT, "complete".getBytes(), "ok".getBytes()));
            segment.append(new WalRecord(WalRecordType.PUT, "partial".getBytes(), "incomplete".getBytes()));
        }
        long firstRecordEnd = WalSegment.HEADER_BYTES
                + new WalRecord(WalRecordType.PUT, "complete".getBytes(), "ok".getBytes()).payload().length;
        try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ch.truncate(firstRecordEnd + 4);
        }
        try (WalSegment segment = WalSegment.openExisting(wal, EngineConfig.WalSyncMode.EVERY_WRITE, 5)) {
            List<WalRecord> records = segment.readAll(true);
            assertThat(records).hasSize(1);
            assertThat(new String(records.get(0).key())).isEqualTo("complete");
        }
    }
}