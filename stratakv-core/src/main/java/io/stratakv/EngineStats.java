package io.stratakv;

public record EngineStats(
        int sstableCount,
        long sstableBytes,
        long memtableBytes,
        long immutableMemtableBytes,
        double bloomFalsePositiveRate,
        double writeAmplification,
        long compactionCount,
        long flushCount
) {}