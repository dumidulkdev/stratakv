package io.stratakv.benchmark;

import io.stratakv.StrataKvEngine;
import io.stratakv.config.EngineConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SimpleBenchmarkRunner {

    public static void main(String[] args) throws Exception {
        int ops = args.length > 0 ? Integer.parseInt(args[0]) : 5_000;
        Path dir = Files.createTempDirectory("stratakv-simple-bench");
        try {
            runScenario(dir, ops, true);
            runScenario(dir, ops, false);
        } finally {
            deleteDir(dir);
        }
    }

    private static void runScenario(Path parent, int ops, boolean bloomOn) throws Exception {
        Path dir = parent.resolve(bloomOn ? "bloom-on" : "bloom-off");
        Files.createDirectories(dir);
        double fpr = bloomOn ? 0.01 : 1.0;
        try (StrataKvEngine engine = StrataKvEngine.open(
                EngineConfig.builder(dir).bloomFalsePositiveRate(fpr).memtableThresholdBytes(32 * 1024).build())) {
            for (int i = 0; i < ops; i++) {
                engine.put(("k" + i).getBytes(), "v".getBytes());
            }
            engine.awaitQuiescence();

            long writeStart = System.nanoTime();
            for (int i = 0; i < ops; i++) {
                engine.put(("w" + i).getBytes(), "v".getBytes());
            }
            long writeElapsed = System.nanoTime() - writeStart;

            List<Long> readLatencies = new ArrayList<>();
            for (int i = 0; i < ops; i++) {
                long start = System.nanoTime();
                engine.get(("k" + (i % ops)).getBytes());
                readLatencies.add(System.nanoTime() - start);
            }
            Collections.sort(readLatencies);

            double writeOps = ops / (writeElapsed / 1e9);
            double readOps = ops / (readLatencies.stream().mapToLong(Long::longValue).sum() / 1e9);
            long p50 = readLatencies.get(readLatencies.size() / 2) / 1_000;
            long p99 = readLatencies.get((int) (readLatencies.size() * 0.99)) / 1_000;

            System.out.printf("bloom=%s writes=%.0f ops/s reads=%.0f ops/s read_p50_us=%d read_p99_us=%d%n",
                    bloomOn, writeOps, readOps, p50, p99);
        }
        deleteDir(dir);
    }

    private static void deleteDir(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
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