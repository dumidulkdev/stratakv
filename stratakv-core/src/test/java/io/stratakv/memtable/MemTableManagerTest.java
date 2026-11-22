package io.stratakv.memtable;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemTableManagerTest {

    @Test
    void rotationOnThreshold() {
        MemTableManager manager = new MemTableManager(64);
        manager.put("a".getBytes(), new byte[40], false);
        assertThat(manager.shouldFlush()).isTrue();
        MemTable rotated = manager.rotate();
        assertThat(rotated.isImmutable()).isTrue();
        assertThat(manager.active()).isNotSameAs(rotated);
        assertThat(manager.immutableMemtables()).containsExactly(rotated);
        assertThat(manager.active().approximateSizeBytes()).isZero();
    }

    @Test
    void multipleRotationsPreserveData() {
        MemTableManager manager = new MemTableManager(32);
        manager.put("x".getBytes(), "1".getBytes(), false);
        MemTable first = manager.rotate();
        manager.put("y".getBytes(), "2".getBytes(), false);
        MemTable second = manager.rotate();
        assertThat(new String(first.get("x".getBytes()))).isEqualTo("1");
        assertThat(new String(second.get("y".getBytes()))).isEqualTo("2");
        assertThat(manager.immutableMemtables()).hasSize(2);
    }
}