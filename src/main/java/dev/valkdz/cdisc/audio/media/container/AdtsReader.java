package dev.valkdz.cdisc.audio.media.container;

import dev.valkdz.cdisc.audio.media.Codec;
import dev.valkdz.cdisc.audio.media.Demuxer;
import dev.valkdz.cdisc.audio.media.MediaInput;
import dev.valkdz.cdisc.audio.media.MediaReader;
import dev.valkdz.cdisc.audio.media.Packet;
import dev.valkdz.cdisc.audio.media.Tags;
import dev.valkdz.cdisc.audio.media.TrackFormat;
import dev.valkdz.cdisc.audio.media.codec.AacDecoder;

import java.io.IOException;

public final class AdtsReader implements Demuxer {

    private static final int SYNC_SEARCH = 256 * 1024;

    private final MediaReader in;
    private final byte[] head = new byte[9];
    private Tags tags = Tags.NONE;
    private TrackFormat format;
    private int sampleRate;
    private long audioStart;
    private long frames;
    private long firstFrameBytes;

    public AdtsReader(MediaInput input) throws IOException {
        this.in = new MediaReader(input);
        byte[] probe = new byte[10];
        while (in.peek(probe, 10) == 10 && Id3.startsTag(probe)) tags = tags.orElse(Id3.readV2(in));
        if (!sync()) throw new IOException("No ADTS frames found");
        audioStart = in.position();
        in.peek(head, 7);
        int profile = (head[2] & 0xC0) >> 6;
        int index = (head[2] & 0x3C) >> 2;
        int channelConfig = ((head[2] & 0x01) << 2) | ((head[3] & 0xC0) >> 6);
        if (index >= AacDecoder.SAMPLE_RATES.length) throw new IOException("Bad ADTS sample rate index " + index);
        sampleRate = AacDecoder.SAMPLE_RATES[index];
        firstFrameBytes = frameLength(head);
        format = TrackFormat.of(Codec.AAC, sampleRate, channelConfig == 1 ? 1 : 2,
                AacDecoder.configFor(profile + 1, index, channelConfig));
    }

    static boolean isHeader(byte[] h) {
        return (h[0] & 0xFF) == 0xFF && (h[1] & 0xF6) == 0xF0 && ((h[2] & 0x3C) >> 2) < 13;
    }

    private static int frameLength(byte[] h) {
        return ((h[3] & 0x03) << 11) | ((h[4] & 0xFF) << 3) | ((h[5] & 0xE0) >> 5);
    }

    private boolean sync() throws IOException {
        byte[] look = new byte[8192 + 7];
        for (int scanned = 0; scanned < SYNC_SEARCH; scanned++) {
            int got = in.peek(look, look.length);
            if (got < 7) return false;
            if (isHeader(look)) {
                int length = frameLength(look);
                if (length >= 7) {
                    if (got < length + 7) return true;
                    byte[] next = new byte[3];
                    System.arraycopy(look, length, next, 0, 3);
                    if (isHeader(next)) return true;
                }
            }
            in.skip(1);
        }
        return false;
    }

    @Override
    public TrackFormat format() {
        return format;
    }

    @Override
    public long durationMs() {
        long length = in.length();
        if (length <= 0 || firstFrameBytes <= 0) return -1;
        return (length - audioStart) / firstFrameBytes * 1024L * 1000L / sampleRate;
    }

    @Override
    public Tags tags() {
        return tags;
    }

    @Override
    public Packet next() throws IOException {
        while (true) {
            if (in.peek(head, 7) < 7) return null;
            if (!isHeader(head)) {
                if (!sync()) return null;
                continue;
            }
            int length = frameLength(head);
            boolean crc = (head[1] & 0x01) == 0;
            int headerLength = crc ? 9 : 7;
            if (length < headerLength) {
                in.skip(1);
                continue;
            }
            in.skip(headerLength);
            byte[] payload = new byte[length - headerLength];
            if (in.readAtMost(payload, 0, payload.length) < payload.length) return null;
            long timeUs = frames * 1024L * 1_000_000L / sampleRate;
            frames += (head[6] & 0x03) + 1;
            return new Packet(payload, timeUs);
        }
    }

    @Override
    public boolean canSeek() {
        return in.canSeek() && durationMs() > 0;
    }

    @Override
    public long seek(long targetMs) throws IOException {
        long duration = durationMs();
        long span = in.length() - audioStart;
        long offset = duration > 0 ? (long) ((double) Math.max(0, targetMs) / duration * span) : 0;
        in.seek(audioStart + Math.min(span, offset));
        sync();
        frames = targetMs * sampleRate / 1024 / 1000;
        return frames * 1024L * 1000L / sampleRate;
    }
}
