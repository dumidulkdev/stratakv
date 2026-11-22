package io.stratakv.admin;

public record StatsResponse(
        int sstableCount,
        long sstableBytes,
        long memtableBytes,
        long immutableMemtableBytes,
        double bloomFalsePositiveRate,
        double writeAmplification,
        long compactionCount,
        long flushCount
) {}