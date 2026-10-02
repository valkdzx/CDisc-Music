package dev.valkdz.cdisc.audio.media;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

public abstract class MediaInput extends InputStream {

    public abstract long position();

    public long length() {
        return -1;
    }

    public boolean canSeek() {
        return false;
    }

    public void seek(long target) throws IOException {
        long at = position();
        if (target < at) throw new IOException("This stream cannot go back to byte " + target);
        skipFully(target - at);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public long skip(long count) throws IOException {
        byte[] scratch = new byte[(int) Math.min(8192, Math.max(1, count))];
        long skipped = 0;
        while (skipped < count) {
            int taken = read(scratch, 0, (int) Math.min(scratch.length, count - skipped));
            if (taken < 0) break;
            skipped += taken;
        }
        return skipped;
    }

    public void skipFully(long count) throws IOException {
        if (skip(count) < count) throw new EOFException("The stream ended while skipping");
    }

    public static MediaInput of(byte[] data) {
        return new Bytes(data);
    }

    public static MediaInput of(File file) throws IOException {
        return new FileInput(file);
    }

    public static MediaInput of(InputStream stream, long length) {
        return stream instanceof MediaInput media ? media : new Sequential(stream, length);
    }

    private static final class Bytes extends MediaInput {
        private final byte[] data;
        private int at;

        Bytes(byte[] data) {
            this.data = data;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) return 0;
            if (at >= data.length) return -1;
            int taken = Math.min(length, data.length - at);
            System.arraycopy(data, at, buffer, offset, taken);
            at += taken;
            return taken;
        }

        @Override
        public long skip(long count) {
            long taken = Math.max(0, Math.min(count, data.length - at));
            at += (int) taken;
            return taken;
        }

        @Override
        public long position() {
            return at;
        }

        @Override
        public long length() {
            return data.length;
        }

        @Override
        public boolean canSeek() {
            return true;
        }

        @Override
        public void seek(long target) {
            at = (int) Math.max(0, Math.min(target, data.length));
        }
    }

    private static final class FileInput extends MediaInput {
        private final RandomAccessFile file;
        private final long length;
        private final byte[] buffer = new byte[64 * 1024];
        private long bufferStart;
        private int bufferFill;
        private long at;

        FileInput(File source) throws IOException {
            this.file = new RandomAccessFile(source, "r");
            this.length = file.length();
        }

        @Override
        public int read(byte[] out, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (at >= length) return -1;
            if (at < bufferStart || at >= bufferStart + bufferFill) {
                file.seek(at);
                bufferStart = at;
                bufferFill = Math.max(0, file.read(buffer, 0, buffer.length));
                if (bufferFill == 0) return -1;
            }
            int from = (int) (at - bufferStart);
            int taken = Math.min(count, bufferFill - from);
            System.arraycopy(buffer, from, out, offset, taken);
            at += taken;
            return taken;
        }

        @Override
        public long skip(long count) {
            long taken = Math.max(0, Math.min(count, length - at));
            at += taken;
            return taken;
        }

        @Override
        public long position() {
            return at;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public boolean canSeek() {
            return true;
        }

        @Override
        public void seek(long target) {
            at = Math.max(0, Math.min(target, length));
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    private static final class Sequential extends MediaInput {
        private final InputStream stream;
        private final long length;
        private long at;

        Sequential(InputStream stream, long length) {
            this.stream = stream;
            this.length = length;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            int taken = stream.read(buffer, offset, count);
            if (taken > 0) at += taken;
            return taken;
        }

        @Override
        public long position() {
            return at;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
