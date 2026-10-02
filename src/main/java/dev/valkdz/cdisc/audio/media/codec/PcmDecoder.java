package dev.valkdz.cdisc.audio.media.codec;

import dev.valkdz.cdisc.audio.media.AudioDecoder;
import dev.valkdz.cdisc.audio.media.Packet;
import dev.valkdz.cdisc.audio.media.TrackFormat;

import java.io.IOException;

public final class PcmDecoder implements AudioDecoder {

    public static final int MAX_FRAMES = 4096;

    private final int sampleRate;
    private final int channels;
    private final int outChannels;
    private final int bytes;
    private final boolean bigEndian;
    private final boolean floating;

    public PcmDecoder(TrackFormat format) throws IOException {
        this.sampleRate = format.sampleRate();
        this.channels = format.channels();
        this.outChannels = Math.min(2, channels);
        this.bytes = format.bitsPerSample() / 8;
        this.bigEndian = format.bigEndian();
        this.floating = format.floating();
        if (bytes < 1 || bytes > 8 || (floating && bytes != 4 && bytes != 8) || channels < 1) {
            throw new IOException("Unsupported PCM: " + format.bitsPerSample() + " bits, " + channels + " channels");
        }
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channels() {
        return outChannels;
    }

    @Override
    public int maxSamples() {
        return MAX_FRAMES;
    }

    @Override
    public void reset() {
    }

    @Override
    public int decode(Packet packet, float[][] out) {
        byte[] data = packet.data();
        int frameBytes = bytes * channels;
        int frames = Math.min(packet.length() / frameBytes, out[0].length);
        int at = packet.offset();
        for (int i = 0; i < frames; i++) {
            float left = 0;
            float right = 0;
            for (int c = 0; c < channels; c++) {
                float v = sample(data, at);
                at += bytes;
                if (c == 0) left = v;
                else if (c == 1) right = v;
                else if (c == 2 || c >= 4) {
                    left += v * 0.7071f;
                    right += v * 0.7071f;
                }
            }
            if (channels == 1) right = left;
            if (channels > 2) {
                left *= 0.5f;
                right *= 0.5f;
            }
            out[0][i] = left;
            if (outChannels > 1) out[1][i] = right;
        }
        return frames;
    }

    private float sample(byte[] d, int at) {
        if (bytes == 1) return bigEndian ? d[at] / 128f : ((d[at] & 0xFF) - 128) / 128f;
        long raw = 0;
        for (int i = 0; i < bytes; i++) {
            int b = d[at + (bigEndian ? i : bytes - 1 - i)] & 0xFF;
            raw = (raw << 8) | b;
        }
        if (floating) return bytes == 4 ? Float.intBitsToFloat((int) raw) : (float) Double.longBitsToDouble(raw);
        int shift = 64 - bytes * 8;
        long signed = (raw << shift) >> shift;
        return (float) (signed / Math.pow(2, bytes * 8 - 1));
    }
}
