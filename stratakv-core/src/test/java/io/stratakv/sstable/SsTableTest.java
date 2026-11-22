package io.stratakv.sstable;

import io.stratakv.bloom.BloomFilter;
import io.stratakv.memtable.InternalKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsTableTest {

    @Test
    void writeAndRead(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("0001.sst");
        List<Map.Entry<InternalKey, byte[]>> entries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add(Map.entry(new InternalKey(("k" + String.format("%03d", i)).getBytes(), i, false),
                    ("v" + i).getBytes()));
        }
        SsTableWriter.writeFromMemtable(file, 256, 0.01, entries);
        try (SsTableReader reader = new SsTableReader(file)) {
            assertThat(reader.mightContain("k050".getBytes())).isTrue();
            assertThat(reader.lookup("k050".getBytes()).kind()).isEqualTo(SsTableReader.LookupKind.VALUE);
            assertThat(reader.lookup("k050".getBytes()).value()).isEqualTo("v50".getBytes());
            assertThat(reader.lookup("k999".getBytes()).kind()).isEqualTo(SsTableReader.LookupKind.MISS);
        }
    }

    @Test
    void corruptedFooterDetected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("0001.sst");
        List<Map.Entry<InternalKey, byte[]>> entries = List.of(
                Map.entry(new InternalKey("a".getBytes(), 1, false), "b".getBytes()));
        SsTableWriter.writeFromMemtable(file, 4096, 0.01, entries);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ch.position(ch.size() - 8);
            ByteBuffer buf = ByteBuffer.allocate(8);
            buf.putLong(0xDEADBEEFL);
            buf.flip();
            ch.write(buf);
        }
        assertThatThrownBy(() -> new SsTableReader(file).close())
                .isInstanceOf(SsTableCorruptionException.class);
    }
}