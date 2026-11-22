package io.stratakv.wal;

public class WalCorruptionException extends RuntimeException {
    public WalCorruptionException(String message) {
        super(message);
    }
}