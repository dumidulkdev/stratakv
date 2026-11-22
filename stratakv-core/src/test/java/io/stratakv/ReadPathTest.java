package io.stratakv;

import io.stratakv.config.EngineConfig;
import io.stratakv.iter.MergingIterator;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Iterator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadPathTest {

    @Test
    void overwriteAndDeleteSemantics(@TempDir Path dir) throws Exception {
        EngineConfig config = EngineConfig.builder(dir)
                .memtableThresholdBytes(64)
                .build();
        try (StrataKvEngine engine = StrataKvEngine.open(config)) {
            engine.put("a".getBytes(), "one".getBytes());
            engine.put("a".getBytes(), "two".getBytes());
            engine.delete("b".getBytes());
            engine.put("b".getBytes(), "beta".getBytes());
            engine.delete("b".getBytes());
            for (int i = 0; i < 80; i++) {
                engine.put(("fill-" + i).getBytes(), new byte[16]);
            }
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> engine.stats().sstableCount() >= 1);
            assertThat(new String(engine.get("a".getBytes()))).isEqualTo("two");
            assertThat(engine.get("b".getBytes())).isNull();
        }
    }

    @Test
    void rangeScanHidesTombstones(@TempDir Path dir) throws Exception {
        EngineConfig config = EngineConfig.builder(dir).memtableThresholdBytes(4096).build();
        try (StrataKvEngine engine = StrataKvEngine.open(config)) {
            engine.put("c1".getBytes(), "1".getBytes());
            engine.put("c2".getBytes(), "2".getBytes());
            engine.put("c3".getBytes(), "3".getBytes());
            engine.delete("c2".getBytes());
            Iterator<MergingIterator.KeyValue> scan =
                    engine.scan("c1".getBytes(), "c9".getBytes());
            List<String> keys = new ArrayList<>();
            while (scan.hasNext()) {
                keys.add(new String(scan.next().key().userKey()));
            }
            assertThat(keys).containsExactly("c1", "c3");
        }
    }
}