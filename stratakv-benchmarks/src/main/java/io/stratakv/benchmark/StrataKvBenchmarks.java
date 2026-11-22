package io.stratakv.benchmark;

import io.stratakv.StrataKvEngine;
import io.stratakv.config.EngineConfig;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class StrataKvBenchmarks {

    @State(Scope.Benchmark)
    public static class EngineState {
        @Param({"true", "false"})
        public boolean bloomEnabled;

        Path dir;
        StrataKvEngine engine;
        Random random = new Random(7);
        byte[][] keys;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            dir = Files.createTempDirectory("stratakv-bench");
            double fpr = bloomEnabled ? 0.01 : 1.0;
            EngineConfig config = EngineConfig.builder(dir)
                    .bloomFalsePositiveRate(fpr)
                    .memtableThresholdBytes(64 * 1024)
                    .build();
            engine = StrataKvEngine.open(config);
            keys = new byte[10_000][];
            for (int i = 0; i < keys.length; i++) {
                String key = "bench-key-" + i;
                keys[i] = key.getBytes();
                engine.put(key.getBytes(), ("value-" + i).getBytes());
            }
            engine.awaitQuiescence();
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            engine.close();
            deleteDir(dir);
        }

        private static void deleteDir(Path root) throws Exception {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Benchmark
    public void randomWrite(EngineState state) throws Exception {
        int i = state.random.nextInt(20_000);
        state.engine.put(("w-" + i).getBytes(), "payload".getBytes());
    }

    @Benchmark
    public byte[] randomReadHit(EngineState state) throws Exception {
        byte[] key = state.keys[state.random.nextInt(state.keys.length)];
        return state.engine.get(key);
    }

    @Benchmark
    public byte[] randomReadMiss(EngineState state) throws Exception {
        int i = state.random.nextInt(1_000_000) + 500_000;
        return state.engine.get(("missing-" + i).getBytes());
    }

    @Benchmark
    public int rangeScan(EngineState state) throws Exception {
        int count = 0;
        var it = state.engine.scan("bench-key-100".getBytes(), "bench-key-200".getBytes());
        while (it.hasNext()) {
            it.next();
            count++;
        }
        return count;
    }
}