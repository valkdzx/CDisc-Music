package dev.valkdz.cdisc.audio.media.container;

import dev.valkdz.cdisc.audio.media.Codec;
import dev.valkdz.cdisc.audio.media.Demuxer;
import dev.valkdz.cdisc.audio.media.MediaInput;
import dev.valkdz.cdisc.audio.media.MediaReader;
import dev.valkdz.cdisc.audio.media.Packet;
import dev.valkdz.cdisc.audio.media.Tags;
import dev.valkdz.cdisc.audio.media.TrackFormat;
import dev.valkdz.cdisc.audio.media.codec.Mp3Decoder;

import java.io.IOException;

public final class Mp3Reader implements Demuxer {

    private static final int MAX_FRAME = 2881;
    private static final int SYNC_SEARCH = 256 * 1024;
    private static final int DECODER_DELAY = 529;

    private final MediaReader in;
    private final byte[] look = new byte[MAX_FRAME + 4];
    private Tags tags = Tags.NONE;
    private long audioStart;
    private long audioEnd = -1;
    private int[] first;
    private int freeFormatBytes;
    private int sampleRate;
    private int channels;
    private int frameSamples;
    private long xingFrames;
    private long xingBytes;
    private int[] toc;
    private int skip;
    private long sampleCursor;
    private TrackFormat format;

    public Mp3Reader(MediaInput input) throws IOException {
        this.in = new MediaReader(input);
        open();
    }

    private void open() throws IOException {
        byte[] head = new byte[10];
        while (in.peek(head, 10) == 10 && Id3.startsTag(head)) {
            tags = tags.orElse(Id3.readV2(in));
        }

        long length = in.length();
        if (in.canSeek() && length > 128) {
            long here = in.position();
            in.seek(length - 128);
            byte[] tail = in.bytes(128);
            Tags v1 = Id3.readV1(tail);
            if (v1 != Tags.NONE) {
                tags = tags.orElse(v1);
                audioEnd = length - 128;
            } else {
                audioEnd = length;
            }
            in.seek(here);
        }

        if (!sync(true)) throw new IOException("No MPEG audio frames found");
        audioStart = in.position();
        int[] h = header(look);
        first = h;
        sampleRate = Mp3Decoder.sampleRateHz(h);
        channels = Mp3Decoder.channelsOf(h);
        frameSamples = Mp3Decoder.frameSamples(h);
        readInfoFrame(h);

        format = TrackFormat.of(Codec.MP3, sampleRate, channels, null).withSkip(skip, 0);
    }

    private static int[] header(byte[] data) {
        return new int[]{data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF, data[3] & 0xFF};
    }

    private int frameLength(int[] h) {
        return Mp3Decoder.frameBytes(h, freeFormatBytes) + Mp3Decoder.padding(h);
    }

    private boolean sync(boolean strict) throws IOException {
        for (int scanned = 0; scanned < SYNC_SEARCH; scanned++) {
            int got = in.peek(look, look.length);
            if (got < 4) return false;
            int[] h = header(look);
            if (Mp3Decoder.hdrValid(h) && (first == null || Mp3Decoder.hdrCompare(first, h))) {
                if (Mp3Decoder.frameBytes(h, 0) == 0 && freeFormatBytes == 0) detectFreeFormat(h, got);
                int length = frameLength(h);
                if (length > 4) {
                    if (got < length + 4) return !strict || got >= length;
                    if (Mp3Decoder.hdrCompare(h, header(slice(length)))) return true;
                }
            }
            in.skip(1);
        }
        return false;
    }

    private byte[] slice(int at) {
        return new byte[]{look[at], look[at + 1], look[at + 2], look[at + 3]};
    }

    private void detectFreeFormat(int[] h, int got) {
        for (int k = 4; k + 4 <= got; k++) {
            if (Mp3Decoder.hdrCompare(h, header(slice(k)))) {
                freeFormatBytes = k - Mp3Decoder.padding(h);
                return;
            }
        }
    }

    private void readInfoFrame(int[] h) throws IOException {
        int length = frameLength(h);
        int got = in.peek(look, Math.min(look.length, length));
        boolean mpeg1 = (h[1] & 0x8) != 0;
        boolean mono = (h[3] & 0xC0) == 0xC0;
        int sideInfo = mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
        int at = 4 + ((h[1] & 1) == 0 ? 2 : 0) + sideInfo;

        if (at + 8 <= got && (tagAt(at, "Xing") || tagAt(at, "Info"))) {
            int flags = (int) be32(at + 4);
            int field = at + 8;
            if ((flags & 1) != 0 && field + 4 <= got) {
                xingFrames = be32(field);
                field += 4;
            }
            if ((flags & 2) != 0 && field + 4 <= got) {
                xingBytes = be32(field);
                field += 4;
            }
            if ((flags & 4) != 0 && field + 100 <= got) {
                toc = new int[100];
                for (int i = 0; i < 100; i++) toc[i] = look[field + i] & 0xFF;
                field += 100;
            }
            if ((flags & 8) != 0) field += 4;
            if (field + 24 <= got && (tagAt(field, "LAME") || tagAt(field, "Lavf") || tagAt(field, "Lavc"))) {
                int delay = ((look[field + 21] & 0xFF) << 4) | ((look[field + 22] & 0xFF) >> 4);
                skip = delay + DECODER_DELAY;
            }
            in.skip(length);
            audioStart = in.position();
            return;
        }

        int vbri = 4 + 32;
        if (vbri + 26 <= got && tagAt(vbri, "VBRI")) {
            xingBytes = be32(vbri + 10);
            xingFrames = be32(vbri + 14);
            in.skip(length);
            audioStart = in.position();
        }
    }

    private boolean tagAt(int at, String tag) {
        for (int i = 0; i < 4; i++) {
            if (look[at + i] != tag.charAt(i)) return false;
        }
        return true;
    }

    private long be32(int at) {
        return ((long) (look[at] & 0xFF) << 24) | ((look[at + 1] & 0xFF) << 16)
                | ((look[at + 2] & 0xFF) << 8) | (look[at + 3] & 0xFF);
    }

    @Override
    public TrackFormat format() {
        return format;
    }

    @Override
    public long durationMs() {
        if (xingFrames > 0) return xingFrames * frameSamples * 1000L / sampleRate;
        int kbps = Mp3Decoder.bitrateKbps(first);
        long end = audioEnd > 0 ? audioEnd : in.length();
        if (kbps <= 0 || end <= audioStart) return -1;
        return (end - audioStart) * 8L / kbps;
    }

    @Override
    public Tags tags() {
        return tags;
    }

    @Override
    public Packet next() throws IOException {
        while (true) {
            if (audioEnd > 0 && in.position() + 4 > audioEnd) return null;
            int got = in.peek(look, 4);
            if (got < 4) return null;
            int[] h = header(look);
            if (!Mp3Decoder.hdrValid(h) || !Mp3Decoder.hdrCompare(first, h)) {
                if (!sync(false)) return null;
                continue;
            }
            int length = frameLength(h);
            if (length <= 4) {
                in.skip(1);
                continue;
            }
            byte[] frame = new byte[length];
            int read = in.readAtMost(frame, 0, length);
            if (read < length) return null;

            long timeUs = sampleCursor * 1_000_000L / sampleRate;
            sampleCursor += Mp3Decoder.frameSamples(h);
            return new Packet(frame, timeUs);
        }
    }

    @Override
    public boolean canSeek() {
        return in.canSeek() && durationMs() > 0;
    }

    @Override
    public long seek(long targetMs) throws IOException {
        long duration = durationMs();
        long end = audioEnd > 0 ? audioEnd : in.length();
        long span = xingBytes > 0 ? xingBytes : end - audioStart;
        targetMs = Math.max(0, Math.min(targetMs, duration));

        long offset;
        if (toc != null && duration > 0) {
            double percent = Math.min(99.999, targetMs * 100.0 / duration);
            int whole = (int) percent;
            double a = toc[whole];
            double b = whole < 99 ? toc[whole + 1] : 256;
            offset = (long) ((a + (b - a) * (percent - whole)) / 256.0 * span);
        } else {
            offset = duration > 0 ? (long) ((double) targetMs / duration * span) : 0;
        }

        in.seek(Math.min(end, audioStart + offset));
        if (!sync(false)) return targetMs;
        sampleCursor = targetMs * sampleRate / 1000;
        return targetMs;
    }
}
