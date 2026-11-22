package io.stratakv.config;

import java.nio.file.Path;
import java.time.Duration;

public final class EngineConfig {

    public enum WalSyncMode {
        EVERY_WRITE,
        GROUP_COMMIT
    }

    private final Path dataDir;
    private final long memtableThresholdBytes;
    private final WalSyncMode walSyncMode;
    private final Duration groupCommitInterval;
    private final double bloomFalsePositiveRate;
    private final int sstableBlockSizeBytes;
    private final int compactionTierCount;

    private EngineConfig(Builder builder) {
        this.dataDir = builder.dataDir;
        this.memtableThresholdBytes = builder.memtableThresholdBytes;
        this.walSyncMode = builder.walSyncMode;
        this.groupCommitInterval = builder.groupCommitInterval;
        this.bloomFalsePositiveRate = builder.bloomFalsePositiveRate;
        this.sstableBlockSizeBytes = builder.sstableBlockSizeBytes;
        this.compactionTierCount = builder.compactionTierCount;
    }

    public static Builder builder(Path dataDir) {
        return new Builder(dataDir);
    }

    public Path dataDir() {
        return dataDir;
    }

    public long memtableThresholdBytes() {
        return memtableThresholdBytes;
    }

    public WalSyncMode walSyncMode() {
        return walSyncMode;
    }

    public Duration groupCommitInterval() {
        return groupCommitInterval;
    }

    public double bloomFalsePositiveRate() {
        return bloomFalsePositiveRate;
    }

    public int sstableBlockSizeBytes() {
        return sstableBlockSizeBytes;
    }

    public int compactionTierCount() {
        return compactionTierCount;
    }

    public static final class Builder {
        private final Path dataDir;
        private long memtableThresholdBytes = 64 * 1024;
        private WalSyncMode walSyncMode = WalSyncMode.EVERY_WRITE;
        private Duration groupCommitInterval = Duration.ofMillis(5);
        private double bloomFalsePositiveRate = 0.01;
        private int sstableBlockSizeBytes = 4096;
        private int compactionTierCount = 4;

        public Builder(Path dataDir) {
            this.dataDir = dataDir;
        }

        public Builder memtableThresholdBytes(long bytes) {
            this.memtableThresholdBytes = bytes;
            return this;
        }

        public Builder walSyncMode(WalSyncMode mode) {
            this.walSyncMode = mode;
            return this;
        }

        public Builder groupCommitInterval(Duration interval) {
            this.groupCommitInterval = interval;
            return this;
        }

        public Builder bloomFalsePositiveRate(double rate) {
            this.bloomFalsePositiveRate = rate;
            return this;
        }

        public Builder sstableBlockSizeBytes(int bytes) {
            this.sstableBlockSizeBytes = bytes;
            return this;
        }

        public Builder compactionTierCount(int count) {
            this.compactionTierCount = count;
            return this;
        }

        public EngineConfig build() {
            return new EngineConfig(this);
        }
    }
}