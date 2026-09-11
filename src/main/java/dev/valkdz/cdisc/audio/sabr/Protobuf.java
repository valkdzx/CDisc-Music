package dev.valkdz.cdisc.audio.sabr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class Protobuf {

    private Protobuf() {
    }

    static final int WIRE_VARINT = 0;
    static final int WIRE_FIXED64 = 1;
    static final int WIRE_BYTES = 2;
    static final int WIRE_FIXED32 = 5;

    public static final class Writer {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer varint(int field, long value) {
            tag(field, WIRE_VARINT);
            writeVarint(value);
            return this;
        }

        public Writer bool(int field, boolean value) {
            return varint(field, value ? 1 : 0);
        }

        public Writer float32(int field, float value) {
            tag(field, WIRE_FIXED32);
            int bits = Float.floatToIntBits(value);
            for (int i = 0; i < 4; i++) {
                out.write((bits >>> (8 * i)) & 0xFF);
            }
            return this;
        }

        public Writer bytes(int field, byte[] value) {
            if (value == null) return this;
            tag(field, WIRE_BYTES);
            writeVarint(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public Writer string(int field, String value) {
            if (value == null) return this;
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        public Writer message(int field, Writer value) {
            if (value == null) return this;
            return bytes(field, value.toByteArray());
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }

        private void tag(int field, int wireType) {
            writeVarint(((long) field << 3) | wireType);
        }

        private void writeVarint(long value) {
            long remaining = value;
            while ((remaining & ~0x7FL) != 0) {
                out.write((int) ((remaining & 0x7F) | 0x80));
                remaining >>>= 7;
            }
            out.write((int) remaining);
        }
    }

    public static final class Reader {

        private final byte[] data;
        private final int end;
        private int pos;

        private int field;
        private int wireType;
        private long varint;
        private int chunkStart;
        private int chunkLength;

        public Reader(byte[] data) {
            this(data, 0, data.length);
        }

        public Reader(byte[] data, int offset, int length) {
            this.data = data;
            this.pos = offset;
            this.end = offset + length;
        }

        public boolean next() {
            if (pos >= end) return false;

            long tag = readVarint();
            field = (int) (tag >>> 3);
            wireType = (int) (tag & 0x7);

            switch (wireType) {
                case WIRE_VARINT -> varint = readVarint();
                case WIRE_FIXED64 -> varint = readFixed(8);
                case WIRE_FIXED32 -> varint = readFixed(4);
                case WIRE_BYTES -> {
                    chunkLength = (int) readVarint();
                    chunkStart = pos;
                    if (chunkLength < 0 || pos + chunkLength > end) {
                        throw new IllegalArgumentException("Length-delimited field runs past the message");
                    }
                    pos += chunkLength;
                }
                default -> throw new IllegalArgumentException("Unknown protobuf wire type " + wireType);
            }
            return true;
        }

        public int field() {
            return field;
        }

        public long longValue() {
            return varint;
        }

        public int intValue() {
            return (int) varint;
        }

        public boolean boolValue() {
            return varint != 0;
        }

        public byte[] bytesValue() {
            byte[] copy = new byte[chunkLength];
            System.arraycopy(data, chunkStart, copy, 0, chunkLength);
            return copy;
        }

        public String stringValue() {
            return new String(data, chunkStart, chunkLength, StandardCharsets.UTF_8);
        }

        public Reader messageValue() {
            return new Reader(data, chunkStart, chunkLength);
        }

        private long readVarint() {
            long result = 0;
            int shift = 0;
            while (true) {
                if (pos >= end) throw new IllegalArgumentException("Truncated varint");
                int b = data[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
                if (shift > 63) throw new IllegalArgumentException("Varint longer than 64 bits");
            }
        }

        private long readFixed(int width) {
            if (pos + width > end) throw new IllegalArgumentException("Truncated fixed-width field");
            long result = 0;
            for (int i = 0; i < width; i++) {
                result |= (long) (data[pos++] & 0xFF) << (8 * i);
            }
            return result;
        }
    }
}
