package io.stratakv.bloom;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterTest {

    @Test
    void falsePositiveRateWithinBounds() {
        BloomFilter filter = BloomFilter.create(0.01, 10_000);
        Random random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            filter.add(("key-" + i).getBytes());
        }
        List<byte[]> probes = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            probes.add(("absent-" + random.nextInt(1_000_000)).getBytes());
        }
        double rate = BloomFilter.measureFalsePositiveRate(filter, probes);
        assertThat(rate).isLessThan(0.05);
    }

    @Test
    void roundTripSerialize() {
        BloomFilter filter = BloomFilter.create(0.01, 100);
        filter.add("hello".getBytes());
        BloomFilter copy = BloomFilter.deserialize(filter.serialize());
        assertThat(copy.mightContain("hello".getBytes())).isTrue();
        assertThat(copy.mightContain("missing".getBytes())).isFalse();
    }
}