package io.stratakv.wal;

public enum WalRecordType {
    PUT((byte) 1),
    DELETE((byte) 2);

    private final byte code;

    WalRecordType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static WalRecordType fromCode(byte code) {
        for (WalRecordType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown WAL record type: " + code);
    }
}