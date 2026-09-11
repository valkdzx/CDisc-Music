package dev.valkdz.cdisc.audio.sabr;

public final class UmpReader {

    private UmpReader() {
    }

    public static final int MEDIA_HEADER = 20;
    public static final int MEDIA = 21;
    public static final int MEDIA_END = 22;
    public static final int NEXT_REQUEST_POLICY = 35;
    public static final int FORMAT_INITIALIZATION_METADATA = 42;
    public static final int SABR_REDIRECT = 43;
    public static final int SABR_ERROR = 44;
    public static final int SABR_CONTEXT_UPDATE = 57;
    public static final int STREAM_PROTECTION_STATUS = 58;
    public static final int END_OF_TRACK = 62;

    @FunctionalInterface
    public interface PartHandler {
        void accept(int type, byte[] body, int offset, int length);
    }

    public static void forEachPart(byte[] body, PartHandler handler) {
        int pos = 0;

        while (pos < body.length) {
            long type = readVarint(body, pos);
            pos += widthOf(body[pos] & 0xFF);
            if (pos >= body.length) {
                throw new IllegalArgumentException("UMP part type with no size after it");
            }

            long size = readVarint(body, pos);
            pos += widthOf(body[pos] & 0xFF);

            if (size < 0 || pos + size > body.length) {
                throw new IllegalArgumentException(
                        "UMP part " + type + " claims " + size + " bytes, " + (body.length - pos) + " left");
            }

            handler.accept((int) type, body, pos, (int) size);
            pos += (int) size;
        }
    }

    static int widthOf(int firstByte) {
        if (firstByte < 0x80) return 1;
        if (firstByte < 0xC0) return 2;
        if (firstByte < 0xE0) return 3;
        if (firstByte < 0xF0) return 4;
        return 5;
    }

    static long readVarint(byte[] data, int offset) {
        int first = byteAt(data, offset);
        int width = widthOf(first);
        if (offset + width > data.length) {
            throw new IllegalArgumentException("Truncated UMP varint");
        }

        return switch (width) {
            case 1 -> first;
            case 2 -> (first & 0x3F) + 64L * byteAt(data, offset + 1);
            case 3 -> (first & 0x1F) + 32L * (byteAt(data, offset + 1)
                    + 256L * byteAt(data, offset + 2));
            case 4 -> (first & 0x0F) + 16L * (byteAt(data, offset + 1)
                    + 256L * (byteAt(data, offset + 2)
                    + 256L * byteAt(data, offset + 3)));

            default -> (byteAt(data, offset + 1)
                    | ((long) byteAt(data, offset + 2) << 8)
                    | ((long) byteAt(data, offset + 3) << 16)
                    | ((long) byteAt(data, offset + 4) << 24)) & 0xFFFFFFFFL;
        };
    }

    private static int byteAt(byte[] data, int offset) {
        if (offset >= data.length) throw new IllegalArgumentException("Truncated UMP varint");
        return data[offset] & 0xFF;
    }
}
