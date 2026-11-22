package io.stratakv.memtable;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemTableTest {

    @Test
    void sizeAccountingGrowsWithPuts() {
        MemTable table = new MemTable(1);
        long before = table.approximateSizeBytes();
        table.put("key".getBytes(), "value".getBytes(), 1, false);
        assertThat(table.approximateSizeBytes()).isGreaterThan(before);
    }

    @Test
    void newestPutWins() {
        MemTable table = new MemTable(1);
        byte[] k = "k".getBytes();
        table.put(k, "v1".getBytes(), 1, false);
        table.put(k, "v2".getBytes(), 2, false);
        assertThat(new String(table.get(k))).isEqualTo("v2");
    }

    @Test
    void tombstoneHidesValue() {
        MemTable table = new MemTable(1);
        byte[] k = "k".getBytes();
        table.put(k, "v".getBytes(), 1, false);
        table.put(k, null, 2, true);
        assertThat(table.get(k)).isNull();
        assertThat(table.isDeleted(k)).isTrue();
    }

    @Test
    void immutableRejectsWrites() {
        MemTable table = new MemTable(1);
        table.makeImmutable();
        assertThatThrownBy(() -> table.put("k".getBytes(), "v".getBytes(), 2, false))
                .isInstanceOf(IllegalStateException.class);
    }
}