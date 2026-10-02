package dev.valkdz.cdisc.audio.media;

import java.io.EOFException;
import java.io.IOException;

public final class MediaReader {

    private final MediaInput input;
    private final byte[] buffer;
    private long bufferStart;
    private int fill;
    private int at;

    public MediaReader(MediaInput input) {
        this(input, 32 * 1024);
    }

    public MediaReader(MediaInput input, int bufferSize) {
        this.input = input;
        this.buffer = new byte[bufferSize];
        this.bufferStart = input.position();
    }

    public MediaInput input() {
        return input;
    }

    public long position() {
        return bufferStart + at;
    }

    public long length() {
        return input.length();
    }

    public boolean canSeek() {
        return input.canSeek();
    }

    public long remaining() {
        long length = length();
        return length < 0 ? Long.MAX_VALUE : length - position();
    }

    public void seek(long target) throws IOException {
        if (target >= bufferStart && target <= bufferStart + fill) {
            at = (int) (target - bufferStart);
            return;
        }
        long here = position();
        if (target > here && !input.canSeek()) {
            skip(target - here);
            return;
        }
        input.seek(target);
        bufferStart = target;
        fill = 0;
        at = 0;
    }

    private boolean refill() throws IOException {
        if (at < fill) return true;
        bufferStart += fill;
        fill = 0;
        at = 0;
        int taken = input.read(buffer, 0, buffer.length);
        if (taken <= 0) return false;
        fill = taken;
        return true;
    }

    public boolean eof() throws IOException {
        return !refill();
    }

    public int read(byte[] out, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (at >= fill) {
            if (length >= buffer.length) {
                bufferStart += fill;
                fill = 0;
                at = 0;
                int taken = input.read(out, offset, length);
                if (taken > 0) bufferStart += taken;
                return taken;
            }
            if (!refill()) return -1;
        }
        int taken = Math.min(length, fill - at);
        System.arraycopy(buffer, at, out, offset, taken);
        at += taken;
        return taken;
    }

    public void readFully(byte[] out, int offset, int length) throws IOException {
        while (length > 0) {
            int taken = read(out, offset, length);
            if (taken < 0) throw new EOFException("The stream ended " + length + " bytes early");
            offset += taken;
            length -= taken;
        }
    }

    public byte[] bytes(int count) throws IOException {
        byte[] out = new byte[count];
        readFully(out, 0, count);
        return out;
    }

    public int readAtMost(byte[] out, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int taken = read(out, offset + total, length - total);
            if (taken < 0) break;
            total += taken;
        }
        return total;
    }

    public void skip(long count) throws IOException {
        while (count > 0) {
            if (at < fill) {
                int taken = (int) Math.min(count, fill - at);
                at += taken;
                count -= taken;
                continue;
            }
            if (input.canSeek()) {
                seek(position() + count);
                return;
            }
            if (!refill()) throw new EOFException("The stream ended while skipping");
        }
    }

    public int u8() throws IOException {
        if (at >= fill && !refill()) throw new EOFException();
        return buffer[at++] & 0xFF;
    }

    public int u16() throws IOException {
        return (u8() << 8) | u8();
    }

    public int u24() throws IOException {
        return (u16() << 8) | u8();
    }

    public long u32() throws IOException {
        return ((long) u16() << 16) | u16();
    }

    public int s32() throws IOException {
        return (int) u32();
    }

    public long u64() throws IOException {
        return (u32() << 32) | u32();
    }

    public int u16le() throws IOException {
        return u8() | (u8() << 8);
    }

    public int u24le() throws IOException {
        return u16le() | (u8() << 16);
    }

    public long u32le() throws IOException {
        return (u16le() & 0xFFFFL) | ((long) u16le() << 16);
    }

    public long u64le() throws IOException {
        return u32le() | (u32le() << 32);
    }

    public int peek(byte[] out, int length) throws IOException {
        if (fill - at < length) {
            if (at > 0) {
                System.arraycopy(buffer, at, buffer, 0, fill - at);
                bufferStart += at;
                fill -= at;
                at = 0;
            }
            while (fill < length && fill < buffer.length) {
                int taken = input.read(buffer, fill, buffer.length - fill);
                if (taken <= 0) break;
                fill += taken;
            }
        }
        int available = Math.min(length, fill - at);
        System.arraycopy(buffer, at, out, 0, available);
        return available;
    }
}
