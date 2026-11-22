package io.stratakv.sstable;

public class SsTableCorruptionException extends RuntimeException {
    public SsTableCorruptionException(String message) {
        super(message);
    }
}