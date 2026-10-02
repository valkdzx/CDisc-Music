package dev.valkdz.cdisc.audio.media.codec;

import dev.valkdz.cdisc.audio.media.AudioDecoder;
import dev.valkdz.cdisc.audio.media.Packet;

import java.io.IOException;

public final class FlacDecoder implements AudioDecoder {

    private static final int[] BLOCK_SIZES = {0, 192, 576, 1152, 2304, 4608, 0, 0, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768};
    private static final int[] SAMPLE_SIZES = {0, 8, 12, 0, 16, 20, 24, 32};

    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final int maxBlock;
    private final int outChannels;
    private final BitReader in = new BitReader();
    private int[][] samples;

    public FlacDecoder(byte[] streamInfo) throws IOException {
        if (streamInfo == null || streamInfo.length < 34) throw new IOException("The FLAC stream has no STREAMINFO");
        BitReader info = new BitReader(streamInfo);
        info.read(16);
        int max = info.read(16);
        info.read(24);
        info.read(24);
        sampleRate = info.read(20);
        channels = info.read(3) + 1;
        bitsPerSample = info.read(5) + 1;
        maxBlock = max > 0 ? max : 65535;
        outChannels = Math.min(channels, 2);
        samples = new int[channels][maxBlock];
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
        return maxBlock;
    }

    @Override
    public void reset() {
    }

    @Override
    public int decode(Packet packet, float[][] out) throws IOException {
        in.reset(packet.data(), packet.offset(), packet.length());
        int sync = in.read(15);
        if (sync != 0x7FFC) throw new IOException("Lost FLAC frame sync");
        in.read(1);
        int blockCode = in.read(4);
        int rateCode = in.read(4);
        int assignment = in.read(4);
        int sizeCode = in.read(3);
        in.read(1);
        readUtf8();
        int blockSize = BLOCK_SIZES[blockCode];
        if (blockCode == 6) blockSize = in.read(8) + 1;
        else if (blockCode == 7) blockSize = in.read(16) + 1;
        if (rateCode == 12) in.read(8);
        else if (rateCode == 13 || rateCode == 14) in.read(16);
        in.read(8);
        int bps = sizeCode == 0 ? bitsPerSample : SAMPLE_SIZES[sizeCode];
        if (blockSize <= 0 || bps <= 0) throw new IOException("Bad FLAC frame header");
        if (blockSize > samples[0].length) samples = new int[channels][blockSize];

        int frameChannels = assignment < 8 ? assignment + 1 : 2;
        for (int c = 0; c < frameChannels && c < channels; c++) {
            int extra = (assignment == 8 && c == 1) || (assignment == 9 && c == 0) || (assignment == 10 && c == 1) ? 1 : 0;
            subframe(samples[c], blockSize, bps + extra);
        }

        int[] a = samples[0];
        int[] b = channels > 1 ? samples[1] : samples[0];
        if (assignment == 8) {
            for (int i = 0; i < blockSize; i++) b[i] = a[i] - b[i];
        } else if (assignment == 9) {
            for (int i = 0; i < blockSize; i++) a[i] += b[i];
        } else if (assignment == 10) {
            for (int i = 0; i < blockSize; i++) {
                int mid = a[i];
                int side = b[i];
                mid = (mid << 1) | (side & 1);
                a[i] = (mid + side) >> 1;
                b[i] = (mid - side) >> 1;
            }
        }

        float scale = 1f / (1L << (bps - 1));
        int count = Math.min(blockSize, out[0].length);
        if (outChannels == 1 || frameChannels == 1) {
            for (int i = 0; i < count; i++) {
                float v = samples[0][i] * scale;
                for (float[] row : out) row[i] = v;
            }
        } else if (frameChannels == 2) {
            for (int i = 0; i < count; i++) {
                out[0][i] = samples[0][i] * scale;
                out[1][i] = samples[1][i] * scale;
            }
        } else {
            mixDown(frameChannels, count, scale, out);
        }
        return count;
    }

    private void mixDown(int n, int count, float scale, float[][] out) {
        float norm = scale / (1 + 0.7071f * (n - 2) / 2f);
        for (int i = 0; i < count; i++) {
            float left = samples[0][i];
            float right = samples[1][i];
            for (int c = 2; c < n; c++) {
                if (n >= 6 && c == 3) continue;
                float v = samples[c][i] * 0.7071f;
                if (c == 2) {
                    left += v;
                    right += v;
                } else if ((c & 1) == 0) {
                    left += v;
                } else {
                    right += v;
                }
            }
            out[0][i] = left * norm;
            out[1][i] = right * norm;
        }
    }

    private void readUtf8() throws IOException {
        int first = in.read(8);
        int extra = 0;
        if ((first & 0x80) == 0) return;
        if ((first & 0xE0) == 0xC0) extra = 1;
        else if ((first & 0xF0) == 0xE0) extra = 2;
        else if ((first & 0xF8) == 0xF0) extra = 3;
        else if ((first & 0xFC) == 0xF8) extra = 4;
        else if ((first & 0xFE) == 0xFC) extra = 5;
        else if (first == 0xFE) extra = 6;
        else throw new IOException("Bad FLAC frame number");
        in.read(8 * extra);
    }

    private void subframe(int[] out, int n, int bps) throws IOException {
        in.read(1);
        int type = in.read(6);
        int wasted = 0;
        if (in.flag()) {
            wasted = 1;
            while (!in.flag()) wasted++;
            bps -= wasted;
        }

        if (type == 0) {
            int value = signed(bps);
            for (int i = 0; i < n; i++) out[i] = value;
        } else if (type == 1) {
            for (int i = 0; i < n; i++) out[i] = signed(bps);
        } else if (type >= 8 && type <= 12) {
            fixed(out, n, bps, type - 8);
        } else if (type >= 32) {
            lpc(out, n, bps, type - 31);
        } else {
            throw new IOException("Reserved FLAC subframe type " + type);
        }

        if (wasted > 0) {
            for (int i = 0; i < n; i++) out[i] <<= wasted;
        }
    }

    private int signed(int bits) {
        if (bits <= 0) return 0;
        long value = in.readLong(bits);
        return (int) ((value << (64 - bits)) >> (64 - bits));
    }

    private void fixed(int[] out, int n, int bps, int order) throws IOException {
        for (int i = 0; i < order; i++) out[i] = signed(bps);
        residual(out, n, order);
        for (int i = order; i < n; i++) {
            switch (order) {
                case 1 -> out[i] += out[i - 1];
                case 2 -> out[i] += 2 * out[i - 1] - out[i - 2];
                case 3 -> out[i] += 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3];
                case 4 -> out[i] += 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4];
                default -> {
                }
            }
        }
    }

    private void lpc(int[] out, int n, int bps, int order) throws IOException {
        for (int i = 0; i < order; i++) out[i] = signed(bps);
        int precision = in.read(4) + 1;
        if (precision == 16) throw new IOException("Bad FLAC LPC precision");
        int shift = signed(5);
        int[] coefs = new int[order];
        for (int i = 0; i < order; i++) coefs[i] = signed(precision);
        residual(out, n, order);
        for (int i = order; i < n; i++) {
            long sum = 0;
            for (int j = 0; j < order; j++) sum += (long) coefs[j] * out[i - 1 - j];
            out[i] += (int) (shift >= 0 ? sum >> shift : sum << -shift);
        }
    }

    private void residual(int[] out, int n, int order) throws IOException {
        int method = in.read(2);
        if (method > 1) throw new IOException("Reserved FLAC residual coding");
        int paramBits = method == 0 ? 4 : 5;
        int escape = method == 0 ? 15 : 31;
        int partitionOrder = in.read(4);
        int partitions = 1 << partitionOrder;
        int at = order;
        for (int p = 0; p < partitions; p++) {
            int count = partitionOrder == 0 ? n - order : (n >> partitionOrder) - (p == 0 ? order : 0);
            if (count < 0 || at + count > n) throw new IOException("Bad FLAC partition");
            int param = in.read(paramBits);
            if (param == escape) {
                int bits = in.read(5);
                for (int i = 0; i < count; i++) out[at++] = signed(bits);
            } else {
                for (int i = 0; i < count; i++) {
                    int q = 0;
                    while (in.bit() == 0) {
                        if (++q > 1 << 24 || in.overrun()) throw new IOException("Runaway FLAC residual");
                    }
                    int value = (q << param) | in.read(param);
                    out[at++] = (value >>> 1) ^ -(value & 1);
                }
            }
        }
    }
}
