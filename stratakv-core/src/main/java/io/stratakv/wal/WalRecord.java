package io.stratakv.wal;

import java.util.Arrays;
import java.util.Objects;

public record WalRecord(WalRecordType type, byte[] key, byte[] value) {

    public WalRecord {
        Objects.requireNonNull(type);
        Objects.requireNonNull(key);
        value = value != null ? value : new byte[0];
        if (type == WalRecordType.DELETE) {
            value = new byte[0];
        }
    }

    public byte[] payload() {
        byte[] out = new byte[1 + 4 + key.length + 4 + value.length];
        int pos = 0;
        out[pos++] = type.code();
        System.arraycopy(intToBytes(key.length), 0, out, pos, 4);
        pos += 4;
        System.arraycopy(key, 0, out, pos, key.length);
        pos += key.length;
        System.arraycopy(intToBytes(value.length), 0, out, pos, 4);
        pos += 4;
        if (value.length > 0) {
            System.arraycopy(value, 0, out, pos, value.length);
        }
        return out;
    }

    public static WalRecord decode(byte[] payload) {
        int pos = 0;
        WalRecordType type = WalRecordType.fromCode(payload[pos++]);
        int keyLen = bytesToInt(payload, pos);
        pos += 4;
        byte[] key = Arrays.copyOfRange(payload, pos, pos + keyLen);
        pos += keyLen;
        int valueLen = bytesToInt(payload, pos);
        pos += 4;
        byte[] value = valueLen == 0 ? new byte[0] : Arrays.copyOfRange(payload, pos, pos + valueLen);
        return new WalRecord(type, key, value);
    }

    private static byte[] intToBytes(int v) {
        return new byte[]{
                (byte) (v >>> 24),
                (byte) (v >>> 16),
                (byte) (v >>> 8),
                (byte) v
        };
    }

    private static int bytesToInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }
}