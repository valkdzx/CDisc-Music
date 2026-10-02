package dev.valkdz.cdisc.audio.media.codec;

public final class BitReader {

    private byte[] data;
    private int start;
    private int end;
    private long position;

    public BitReader() {
    }

    public BitReader(byte[] data) {
        reset(data, 0, data.length);
    }

    public BitReader reset(byte[] data, int offset, int length) {
        this.data = data;
        this.start = offset;
        this.end = offset + length;
        this.position = (long) offset * 8;
        return this;
    }

    public int read(int bits) {
        int value = 0;
        for (int i = 0; i < bits; i++) value = (value << 1) | bit();
        return value;
    }

    public long readLong(int bits) {
        long value = 0;
        for (int i = 0; i < bits; i++) value = (value << 1) | bit();
        return value;
    }

    public int bit() {
        int index = (int) (position >> 3);
        if (index >= end) {
            position++;
            return 0;
        }
        int value = (data[index] >> (7 - (int) (position & 7))) & 1;
        position++;
        return value;
    }

    public boolean flag() {
        return bit() != 0;
    }

    public void skip(long bits) {
        position += bits;
    }

    public void byteAlign() {
        position = (position + 7) & ~7L;
    }

    public long bitPosition() {
        return position - (long) start * 8;
    }

    public long bitsLeft() {
        return (long) end * 8 - position;
    }

    public boolean overrun() {
        return position > (long) end * 8;
    }
}
