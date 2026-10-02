package dev.valkdz.cdisc.audio.media.codec;

import dev.valkdz.cdisc.audio.media.AudioDecoder;
import dev.valkdz.cdisc.audio.media.Packet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AacDecoder implements AudioDecoder {

    public static final int[] SAMPLE_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private static final int ONLY_LONG = 0;
    private static final int LONG_START = 1;
    private static final int EIGHT_SHORT = 2;
    private static final int LONG_STOP = 3;

    private static final int ZERO_HCB = 0;
    private static final int ESC_HCB = 11;
    private static final int NOISE_HCB = 13;
    private static final int INTENSITY_HCB2 = 14;
    private static final int INTENSITY_HCB = 15;

    private static final int SCE = 0;
    private static final int CPE = 1;
    private static final int CCE = 2;
    private static final int LFE = 3;
    private static final int DSE = 4;
    private static final int PCE = 5;
    private static final int FIL = 6;
    private static final int END = 7;

    private static final HuffmanTree SCALEFACTORS = new HuffmanTree(AacTables.SCALEFACTOR_CODES, AacTables.SCALEFACTOR_BITS);
    private static final HuffmanTree[] SPECTRAL = new HuffmanTree[12];
    private static final float[] POW43 = new float[8192];
    private static final float[] GAIN = new float[512];
    private static final float[][] SINE = {sine(2048), sine(256)};
    private static final float[][] KBD = {kbd(2048, 4), kbd(256, 6)};

    static {
        for (int i = 1; i <= 11; i++) SPECTRAL[i] = new HuffmanTree(AacTables.SPECTRAL_CODES[i - 1], AacTables.SPECTRAL_BITS[i - 1]);
        for (int i = 0; i < POW43.length; i++) POW43[i] = (float) Math.pow(i, 4.0 / 3.0);
        for (int i = 0; i < GAIN.length; i++) GAIN[i] = (float) Math.pow(2.0, 0.25 * (i - 256));
    }

    public record Config(int objectType, int sampleRateIndex, int sampleRate, int channelConfig,
                         boolean sbr, int extensionSampleRate) {

        public int outputChannels() {
            return channelConfig == 1 ? 1 : 2;
        }
    }

    public static Config parseConfig(byte[] asc) throws IOException {
        if (asc == null || asc.length < 2) throw new IOException("The AAC stream carries no AudioSpecificConfig");
        BitReader in = new BitReader(asc);
        int objectType = objectType(in);
        int index = in.read(4);
        int rate = index == 15 ? in.read(24) : index < SAMPLE_RATES.length ? SAMPLE_RATES[index] : 0;
        int channelConfig = in.read(4);
        boolean sbr = false;
        int extensionRate = 0;
        if (objectType == 5 || objectType == 29) {
            sbr = true;
            int extIndex = in.read(4);
            extensionRate = extIndex == 15 ? in.read(24) : extIndex < SAMPLE_RATES.length ? SAMPLE_RATES[extIndex] : 0;
            objectType = objectType(in);
        }
        if (objectType != 2 && objectType != 1) {
            throw new IOException("AAC object type " + objectType + " is not supported, only AAC-LC");
        }
        if (in.flag()) throw new IOException("AAC with 960-sample frames is not supported");
        if (rate <= 0) throw new IOException("The AAC stream names no sample rate");
        if (index == 15) index = nearestIndex(rate);
        return new Config(objectType, index, rate, channelConfig, sbr, extensionRate);
    }

    private static int objectType(BitReader in) {
        int type = in.read(5);
        return type == 31 ? 32 + in.read(6) : type;
    }

    private static int nearestIndex(int rate) {
        int best = 0;
        for (int i = 1; i < SAMPLE_RATES.length; i++) {
            if (Math.abs(SAMPLE_RATES[i] - rate) < Math.abs(SAMPLE_RATES[best] - rate)) best = i;
        }
        return best;
    }

    public static byte[] configFor(int objectType, int sampleRateIndex, int channelConfig) {
        int value = (objectType << 11) | (sampleRateIndex << 7) | (channelConfig << 3);
        return new byte[]{(byte) (value >> 8), (byte) value};
    }

    private final Config config;
    private final int outChannels;
    private final BitReader in = new BitReader();
    private final List<Channel> channels = new ArrayList<>();
    private final Imdct longImdct = new Imdct(2048, 1.0 / (1024.0 * 32768.0));
    private final Imdct shortImdct = new Imdct(256, 1.0 / (128.0 * 32768.0));
    private final float[] buffer = new float[2048];
    private final float[] shortBuffer = new float[256];
    private int noiseState = 0x1F2E3D4C;

    public AacDecoder(byte[] asc) throws IOException {
        this.config = parseConfig(asc);
        this.outChannels = config.outputChannels();
    }

    public Config config() {
        return config;
    }

    @Override
    public int sampleRate() {
        return config.sampleRate();
    }

    @Override
    public int channels() {
        return outChannels;
    }

    @Override
    public int maxSamples() {
        return 1024;
    }

    @Override
    public void reset() {
        for (Channel channel : channels) channel.reset();
    }

    private static final class Ics {
        int windowSequence;
        int windowShape;
        int maxSfb;
        int numWindows;
        int numWindowGroups;
        final int[] groupLength = new int[8];
        int[] swbOffset;
        int numSwb;
        final int[][] sfbCb = new int[8][64];
        final int[][] sf = new int[8][64];
        boolean tnsPresent;
        final int[] nFilt = new int[8];
        final int[] coefRes = new int[8];
        final int[][] tnsLength = new int[8][4];
        final int[][] tnsOrder = new int[8][4];
        final int[][] tnsDirection = new int[8][4];
        final int[][] tnsCompress = new int[8][4];
        final int[][][] tnsCoef = new int[8][4][32];

        void copyInfo(Ics from) {
            windowSequence = from.windowSequence;
            windowShape = from.windowShape;
            maxSfb = from.maxSfb;
            numWindows = from.numWindows;
            numWindowGroups = from.numWindowGroups;
            System.arraycopy(from.groupLength, 0, groupLength, 0, 8);
            swbOffset = from.swbOffset;
            numSwb = from.numSwb;
        }
    }

    private static final class Channel {
        final float[] overlap = new float[1024];
        final float[] spec = new float[1024];
        final int[] quant = new int[1024];
        final Ics ics = new Ics();
        int prevShape;
        int kind;

        void reset() {
            Arrays.fill(overlap, 0);
            prevShape = 0;
        }
    }

    @Override
    public int decode(Packet packet, float[][] out) throws IOException {
        in.reset(packet.data(), packet.offset(), packet.length());
        int used = 0;
        List<Channel> frame = new ArrayList<>(4);

        while (true) {
            if (in.bitsLeft() < 3) break;
            int id = in.read(3);
            if (id == END) break;
            switch (id) {
                case SCE, LFE -> {
                    in.read(4);
                    Channel channel = channel(used++, id);
                    decodeIcs(channel, false);
                    finish(channel);
                    frame.add(channel);
                }
                case CPE -> {
                    in.read(4);
                    Channel left = channel(used++, CPE);
                    Channel right = channel(used++, CPE);
                    decodeCpe(left, right);
                    frame.add(left);
                    frame.add(right);
                }
                case CCE -> throw new IOException("AAC coupling channels are not supported");
                case DSE -> skipDataStream();
                case PCE -> skipProgramConfig();
                case FIL -> {
                    int count = in.read(4);
                    if (count == 15) count += in.read(8) - 1;
                    in.skip(count * 8L);
                }
                default -> throw new IOException("Unknown AAC element " + id);
            }
            if (in.overrun()) throw new IOException("The AAC frame ended early");
        }
        if (frame.isEmpty()) return 0;
        mix(frame, out);
        return 1024;
    }

    private Channel channel(int index, int kind) {
        while (channels.size() <= index) channels.add(new Channel());
        Channel channel = channels.get(index);
        channel.kind = kind;
        return channel;
    }

    private void mix(List<Channel> frame, float[][] out) {
        if (frame.size() == 1 || outChannels == 1) {
            float[] mono = frame.get(0).spec;
            for (int c = 0; c < out.length; c++) System.arraycopy(mono, 0, out[c], 0, 1024);
            return;
        }
        if (frame.size() == 2 && frame.get(0).kind == CPE) {
            System.arraycopy(frame.get(0).spec, 0, out[0], 0, 1024);
            System.arraycopy(frame.get(1).spec, 0, out[1], 0, 1024);
            return;
        }

        Arrays.fill(out[0], 0, 1024, 0);
        Arrays.fill(out[1], 0, 1024, 0);
        double leftGain = 0;
        boolean firstPair = true;
        for (int i = 0; i < frame.size(); i++) {
            Channel channel = frame.get(i);
            if (channel.kind == LFE) continue;
            if (channel.kind == CPE) {
                float gain = firstPair ? 1f : 0.7071f;
                firstPair = false;
                add(out[0], channel.spec, gain);
                add(out[1], frame.get(++i).spec, gain);
                leftGain += gain;
            } else {
                add(out[0], channel.spec, 0.7071f);
                add(out[1], channel.spec, 0.7071f);
                leftGain += 0.7071f;
            }
        }
        float norm = leftGain > 1 ? (float) (1 / leftGain) : 1f;
        for (int i = 0; i < 1024; i++) {
            out[0][i] *= norm;
            out[1][i] *= norm;
        }
    }

    private static void add(float[] into, float[] from, float gain) {
        for (int i = 0; i < 1024; i++) into[i] += from[i] * gain;
    }

    private void skipDataStream() {
        in.read(4);
        boolean align = in.flag();
        int count = in.read(8);
        if (count == 255) count += in.read(8);
        if (align) in.byteAlign();
        in.skip(count * 8L);
    }

    private void skipProgramConfig() {
        in.read(4);
        in.read(2);
        in.read(4);
        int front = in.read(4);
        int side = in.read(4);
        int back = in.read(4);
        int lfe = in.read(2);
        int assoc = in.read(3);
        int cc = in.read(4);
        if (in.flag()) in.read(4);
        if (in.flag()) in.read(4);
        if (in.flag()) in.read(3);
        in.skip((front + side + back) * 5L + lfe * 4L + assoc * 4L + cc * 5L);
        in.byteAlign();
        int comment = in.read(8);
        in.skip(comment * 8L);
    }

    private void readIcsInfo(Ics ics) throws IOException {
        in.read(1);
        ics.windowSequence = in.read(2);
        ics.windowShape = in.read(1);
        int sfi = config.sampleRateIndex();
        if (ics.windowSequence == EIGHT_SHORT) {
            ics.maxSfb = in.read(4);
            int grouping = in.read(7);
            ics.numWindows = 8;
            ics.numWindowGroups = 1;
            ics.groupLength[0] = 1;
            for (int i = 0; i < 7; i++) {
                if ((grouping & (1 << (6 - i))) != 0) {
                    ics.groupLength[ics.numWindowGroups - 1]++;
                } else {
                    ics.groupLength[ics.numWindowGroups++] = 1;
                }
            }
            ics.swbOffset = AacTables.SWB_OFFSET_128[sfi];
            ics.numSwb = AacTables.NUM_SWB_128[sfi];
        } else {
            ics.maxSfb = in.read(6);
            ics.numWindows = 1;
            ics.numWindowGroups = 1;
            ics.groupLength[0] = 1;
            ics.swbOffset = AacTables.SWB_OFFSET_1024[sfi];
            ics.numSwb = AacTables.NUM_SWB_1024[sfi];
            if (in.flag()) throw new IOException("AAC prediction is not supported");
        }
        if (ics.maxSfb > ics.numSwb) throw new IOException("AAC max_sfb " + ics.maxSfb + " is out of range");
    }

    private void decodeCpe(Channel left, Channel right) throws IOException {
        boolean commonWindow = in.flag();
        int msMaskPresent = 0;
        boolean[][] msUsed = null;
        if (commonWindow) {
            readIcsInfo(left.ics);
            right.ics.copyInfo(left.ics);
            msMaskPresent = in.read(2);
            if (msMaskPresent == 1) {
                msUsed = new boolean[left.ics.numWindowGroups][left.ics.maxSfb];
                for (int g = 0; g < left.ics.numWindowGroups; g++) {
                    for (int sfb = 0; sfb < left.ics.maxSfb; sfb++) msUsed[g][sfb] = in.flag();
                }
            } else if (msMaskPresent == 3) {
                throw new IOException("Reserved AAC ms_mask_present");
            }
        }
        decodeIcs(left, commonWindow);
        decodeIcs(right, commonWindow);

        if (commonWindow) {
            noise(left, right, msMaskPresent, msUsed);
            if (msMaskPresent > 0) midSide(left, right, msMaskPresent, msUsed);
            intensity(left, right, msMaskPresent, msUsed);
        } else {
            noise(left, null, 0, null);
            noise(right, null, 0, null);
            intensity(left, right, 0, null);
        }
        tnsAndFilter(left);
        tnsAndFilter(right);
    }

    private void finish(Channel channel) {
        noise(channel, null, 0, null);
        tnsAndFilter(channel);
    }

    private void decodeIcs(Channel channel, boolean commonWindow) throws IOException {
        Ics ics = channel.ics;
        int globalGain = in.read(8);
        if (!commonWindow) readIcsInfo(ics);
        readSections(ics);
        readScalefactors(ics, globalGain);

        boolean pulses = in.flag();
        int pulseCount = 0;
        int pulseStart = 0;
        int[] pulseOffset = null;
        int[] pulseAmp = null;
        if (pulses) {
            if (ics.windowSequence == EIGHT_SHORT) throw new IOException("AAC pulse data in a short window");
            pulseCount = in.read(2) + 1;
            pulseStart = in.read(6);
            pulseOffset = new int[pulseCount];
            pulseAmp = new int[pulseCount];
            for (int i = 0; i < pulseCount; i++) {
                pulseOffset[i] = in.read(5);
                pulseAmp[i] = in.read(4);
            }
        }

        ics.tnsPresent = in.flag();
        if (ics.tnsPresent) readTns(ics);
        if (in.flag()) throw new IOException("AAC gain control is not supported");

        int[] quant = channel.quant;
        Arrays.fill(quant, 0);
        readSpectrum(ics, quant);

        if (pulses) {
            int k = ics.swbOffset[Math.min(pulseStart, ics.numSwb)];
            for (int i = 0; i < pulseCount; i++) {
                k += pulseOffset[i];
                if (k >= 1024) break;
                quant[k] += quant[k] > 0 ? pulseAmp[i] : -pulseAmp[i];
            }
        }
        dequantize(ics, quant, channel.spec);
    }

    private void readSections(Ics ics) throws IOException {
        int bits = ics.windowSequence == EIGHT_SHORT ? 3 : 5;
        int escape = (1 << bits) - 1;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            int k = 0;
            while (k < ics.maxSfb) {
                int cb = in.read(4);
                if (cb == 12) throw new IOException("Reserved AAC codebook");
                int length = 0;
                int increment;
                while ((increment = in.read(bits)) == escape) {
                    length += escape;
                    if (in.overrun()) throw new IOException("The AAC section data ran out");
                }
                length += increment;
                if (k + length > ics.maxSfb) throw new IOException("An AAC section runs past max_sfb");
                for (int sfb = k; sfb < k + length; sfb++) ics.sfbCb[g][sfb] = cb;
                k += length;
            }
        }
    }

    private void readScalefactors(Ics ics, int globalGain) throws IOException {
        int scale = globalGain;
        int noise = globalGain - 90;
        int position = 0;
        boolean firstNoise = true;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                int cb = ics.sfbCb[g][sfb];
                if (cb == ZERO_HCB) {
                    ics.sf[g][sfb] = 0;
                } else if (cb == INTENSITY_HCB || cb == INTENSITY_HCB2) {
                    position += sfDelta();
                    ics.sf[g][sfb] = position;
                } else if (cb == NOISE_HCB) {
                    if (firstNoise) {
                        firstNoise = false;
                        noise += in.read(9) - 256;
                    } else {
                        noise += sfDelta();
                    }
                    ics.sf[g][sfb] = noise;
                } else {
                    scale += sfDelta();
                    ics.sf[g][sfb] = scale;
                }
            }
        }
    }

    private int sfDelta() throws IOException {
        int symbol = SCALEFACTORS.decode(in);
        if (symbol < 0) throw new IOException("Bad AAC scalefactor code");
        return symbol - 60;
    }

    private void readTns(Ics ics) {
        boolean shortWindow = ics.windowSequence == EIGHT_SHORT;
        for (int w = 0; w < ics.numWindows; w++) {
            ics.nFilt[w] = in.read(shortWindow ? 1 : 2);
            if (ics.nFilt[w] == 0) continue;
            ics.coefRes[w] = in.read(1);
            for (int f = 0; f < ics.nFilt[w]; f++) {
                ics.tnsLength[w][f] = in.read(shortWindow ? 4 : 6);
                int order = in.read(shortWindow ? 3 : 5);
                ics.tnsOrder[w][f] = order;
                if (order == 0) continue;
                ics.tnsDirection[w][f] = in.read(1);
                ics.tnsCompress[w][f] = in.read(1);
                int bits = ics.coefRes[w] + 3 - ics.tnsCompress[w][f];
                for (int i = 0; i < order; i++) ics.tnsCoef[w][f][i] = in.read(bits);
            }
        }
    }

    private void readSpectrum(Ics ics, int[] quant) throws IOException {
        int windowBase = 0;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                int cb = ics.sfbCb[g][sfb];
                if (cb == ZERO_HCB || cb >= NOISE_HCB) continue;
                int start = ics.swbOffset[sfb];
                int end = ics.swbOffset[sfb + 1];
                for (int w = 0; w < ics.groupLength[g]; w++) {
                    int base = (windowBase + w) * 128;
                    for (int k = start; k < end; ) {
                        k += readCodeword(cb, quant, base + k);
                    }
                }
            }
            windowBase += ics.groupLength[g];
        }
    }

    private int readCodeword(int cb, int[] quant, int at) throws IOException {
        int index = SPECTRAL[cb].decode(in);
        if (index < 0) throw new IOException("Bad AAC spectral code in book " + cb);
        if (cb <= 4) {
            int w = index / 27;
            int x = (index / 9) % 3;
            int y = (index / 3) % 3;
            int z = index % 3;
            if (cb <= 2) {
                quant[at] = w - 1;
                quant[at + 1] = x - 1;
                quant[at + 2] = y - 1;
                quant[at + 3] = z - 1;
            } else {
                quant[at] = w;
                quant[at + 1] = x;
                quant[at + 2] = y;
                quant[at + 3] = z;
                for (int i = 0; i < 4; i++) {
                    if (quant[at + i] != 0 && in.flag()) quant[at + i] = -quant[at + i];
                }
            }
            return 4;
        }

        int mod = cb <= 6 ? 9 : cb <= 8 ? 8 : cb <= 10 ? 13 : 17;
        int y = index / mod;
        int z = index % mod;
        if (cb <= 6) {
            quant[at] = y - 4;
            quant[at + 1] = z - 4;
            return 2;
        }
        boolean negY = y != 0 && in.flag();
        boolean negZ = z != 0 && in.flag();
        if (cb == ESC_HCB) {
            if (y == 16) y = escape();
            if (z == 16) z = escape();
        }
        quant[at] = negY ? -y : y;
        quant[at + 1] = negZ ? -z : z;
        return 2;
    }

    private int escape() throws IOException {
        int n = 0;
        while (in.flag()) {
            if (++n > 8) throw new IOException("Bad AAC escape sequence");
        }
        return (1 << (n + 4)) + in.read(n + 4);
    }

    private void dequantize(Ics ics, int[] quant, float[] spec) {
        Arrays.fill(spec, 0);
        int windowBase = 0;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                int cb = ics.sfbCb[g][sfb];
                if (cb == ZERO_HCB || cb >= NOISE_HCB) continue;
                float gain = gain(ics.sf[g][sfb] - 100);
                int start = ics.swbOffset[sfb];
                int end = ics.swbOffset[sfb + 1];
                for (int w = 0; w < ics.groupLength[g]; w++) {
                    int base = (windowBase + w) * 128;
                    for (int k = start; k < end; k++) {
                        int q = quant[base + k];
                        if (q == 0) continue;
                        float v = q > 0 ? POW43[Math.min(q, 8191)] : -POW43[Math.min(-q, 8191)];
                        spec[base + k] = v * gain;
                    }
                }
            }
            windowBase += ics.groupLength[g];
        }
    }

    private static float gain(int exponent) {
        int i = exponent + 256;
        return i >= 0 && i < GAIN.length ? GAIN[i] : (float) Math.pow(2.0, 0.25 * exponent);
    }

    private int random() {
        noiseState = noiseState * 1664525 + 1013904223;
        return noiseState;
    }

    private void noise(Channel channel, Channel other, int msMaskPresent, boolean[][] msUsed) {
        Ics ics = channel.ics;
        int windowBase = 0;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                boolean here = ics.sfbCb[g][sfb] == NOISE_HCB;
                boolean there = other != null && other.ics.sfbCb[g][sfb] == NOISE_HCB;
                if (!here && !there) continue;
                boolean correlated = here && there
                        && (msMaskPresent == 2 || (msMaskPresent == 1 && msUsed[g][sfb]));
                int start = ics.swbOffset[sfb];
                int end = ics.swbOffset[sfb + 1];
                for (int w = 0; w < ics.groupLength[g]; w++) {
                    int base = (windowBase + w) * 128;
                    if (here) fillNoise(channel.spec, base + start, end - start, ics.sf[g][sfb]);
                    if (there) {
                        if (correlated) {
                            float scale = noiseScale(channel.spec, base + start, end - start, other.ics.sf[g][sfb]);
                            for (int k = start; k < end; k++) other.spec[base + k] = channel.spec[base + k] * scale;
                        } else {
                            fillNoise(other.spec, base + start, end - start, other.ics.sf[g][sfb]);
                        }
                    }
                }
            }
            windowBase += ics.groupLength[g];
        }
    }

    private void fillNoise(float[] spec, int at, int length, int energy) {
        double sum = 0;
        for (int i = 0; i < length; i++) {
            float value = random();
            spec[at + i] = value;
            sum += (double) value * value;
        }
        float scale = (float) (Math.pow(2.0, 0.25 * energy) / Math.sqrt(Math.max(sum, 1e-30)));
        for (int i = 0; i < length; i++) spec[at + i] *= scale;
    }

    private static float noiseScale(float[] spec, int at, int length, int energy) {
        double sum = 0;
        for (int i = 0; i < length; i++) sum += (double) spec[at + i] * spec[at + i];
        return (float) (Math.pow(2.0, 0.25 * energy) / Math.sqrt(Math.max(sum, 1e-30)));
    }

    private static void midSide(Channel left, Channel right, int msMaskPresent, boolean[][] msUsed) {
        Ics ics = left.ics;
        int windowBase = 0;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                if (msMaskPresent == 1 && !msUsed[g][sfb]) continue;
                int cbRight = right.ics.sfbCb[g][sfb];
                if (cbRight == INTENSITY_HCB || cbRight == INTENSITY_HCB2) continue;
                if (ics.sfbCb[g][sfb] == NOISE_HCB || cbRight == NOISE_HCB) continue;
                int start = ics.swbOffset[sfb];
                int end = ics.swbOffset[sfb + 1];
                for (int w = 0; w < ics.groupLength[g]; w++) {
                    int base = (windowBase + w) * 128;
                    for (int k = start; k < end; k++) {
                        float m = left.spec[base + k];
                        float s = right.spec[base + k];
                        left.spec[base + k] = m + s;
                        right.spec[base + k] = m - s;
                    }
                }
            }
            windowBase += ics.groupLength[g];
        }
    }

    private static void intensity(Channel left, Channel right, int msMaskPresent, boolean[][] msUsed) {
        Ics ics = right.ics;
        int windowBase = 0;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                int cb = ics.sfbCb[g][sfb];
                if (cb != INTENSITY_HCB && cb != INTENSITY_HCB2) continue;
                float sign = cb == INTENSITY_HCB ? 1f : -1f;
                if (msMaskPresent == 1 && msUsed[g][sfb]) sign = -sign;
                float scale = sign * (float) Math.pow(0.5, 0.25 * ics.sf[g][sfb]);
                int start = ics.swbOffset[sfb];
                int end = ics.swbOffset[sfb + 1];
                for (int w = 0; w < ics.groupLength[g]; w++) {
                    int base = (windowBase + w) * 128;
                    for (int k = start; k < end; k++) right.spec[base + k] = left.spec[base + k] * scale;
                }
            }
            windowBase += ics.groupLength[g];
        }
    }

    private final float[] lpc = new float[33];
    private final float[] parcor = new float[32];
    private final float[] lpcTmp = new float[33];
    private final float[] state = new float[32];

    private void tns(Channel channel) {
        Ics ics = channel.ics;
        boolean shortWindow = ics.windowSequence == EIGHT_SHORT;
        int sfi = config.sampleRateIndex();
        int maxBands = shortWindow ? AacTables.TNS_MAX_BANDS_128[sfi] : AacTables.TNS_MAX_BANDS_1024[sfi];
        int limit = Math.min(maxBands, ics.maxSfb);

        for (int w = 0; w < ics.numWindows; w++) {
            int bottom = ics.numSwb;
            for (int f = 0; f < ics.nFilt[w]; f++) {
                int top = bottom;
                bottom = Math.max(top - ics.tnsLength[w][f], 0);
                int order = ics.tnsOrder[w][f];
                if (order == 0) continue;

                int resBits = ics.coefRes[w] + 3;
                int bits = resBits - ics.tnsCompress[w][f];
                double iqfac = ((1 << (resBits - 1)) - 0.5) / (Math.PI / 2);
                double iqfacM = ((1 << (resBits - 1)) + 0.5) / (Math.PI / 2);
                for (int i = 0; i < order; i++) {
                    int value = ics.tnsCoef[w][f][i];
                    if ((value & (1 << (bits - 1))) != 0) value -= 1 << bits;
                    parcor[i] = (float) Math.sin(value / (value >= 0 ? iqfac : iqfacM));
                }
                lpc[0] = 1;
                for (int m = 1; m <= order; m++) {
                    for (int i = 1; i < m; i++) lpcTmp[i] = lpc[i] + parcor[m - 1] * lpc[m - i];
                    for (int i = 1; i < m; i++) lpc[i] = lpcTmp[i];
                    lpc[m] = parcor[m - 1];
                }

                int start = ics.swbOffset[Math.min(bottom, limit)];
                int end = ics.swbOffset[Math.min(top, limit)];
                int size = end - start;
                if (size <= 0) continue;
                int inc = 1;
                int at = w * 128 + start;
                if (ics.tnsDirection[w][f] != 0) {
                    inc = -1;
                    at = w * 128 + end - 1;
                }
                Arrays.fill(state, 0, order, 0);
                float[] spec = channel.spec;
                for (int m = 0; m < size; m++, at += inc) {
                    float y = spec[at];
                    for (int j = 0; j < order; j++) y -= lpc[j + 1] * state[j];
                    for (int j = order - 1; j > 0; j--) state[j] = state[j - 1];
                    state[0] = y;
                    spec[at] = y;
                }
            }
        }
    }

    private void tnsAndFilter(Channel channel) {
        if (channel.ics.tnsPresent) tns(channel);
        filterbank(channel);
    }

    private void filterbank(Channel channel) {
        Ics ics = channel.ics;
        float[] spec = channel.spec;
        float[] overlap = channel.overlap;
        float[] longPrev = channel.prevShape == 1 ? KBD[0] : SINE[0];
        float[] longCur = ics.windowShape == 1 ? KBD[0] : SINE[0];
        float[] shortPrev = channel.prevShape == 1 ? KBD[1] : SINE[1];
        float[] shortCur = ics.windowShape == 1 ? KBD[1] : SINE[1];
        float[] z = buffer;

        if (ics.windowSequence == EIGHT_SHORT) {
            Arrays.fill(z, 0);
            for (int w = 0; w < 8; w++) {
                shortImdct.transform(spec, w * 128, shortBuffer, 0);
                float[] rising = w == 0 ? shortPrev : shortCur;
                int at = 448 + w * 128;
                for (int i = 0; i < 128; i++) z[at + i] += shortBuffer[i] * rising[i];
                for (int i = 128; i < 256; i++) z[at + i] += shortBuffer[i] * shortCur[i];
            }
        } else {
            longImdct.transform(spec, 0, z, 0);
            if (ics.windowSequence == LONG_STOP) {
                for (int i = 0; i < 448; i++) z[i] = 0;
                for (int i = 448; i < 576; i++) z[i] *= shortPrev[i - 448];
            } else {
                for (int i = 0; i < 1024; i++) z[i] *= longPrev[i];
            }
            if (ics.windowSequence == LONG_START) {
                for (int i = 1472; i < 1600; i++) z[i] *= shortCur[i - 1472 + 128];
                for (int i = 1600; i < 2048; i++) z[i] = 0;
            } else {
                for (int i = 1024; i < 2048; i++) z[i] *= longCur[i];
            }
        }

        for (int i = 0; i < 1024; i++) spec[i] = z[i] + overlap[i];
        System.arraycopy(z, 1024, overlap, 0, 1024);
        channel.prevShape = ics.windowShape;
    }

    private static float[] sine(int n) {
        float[] window = new float[n];
        for (int i = 0; i < n; i++) window[i] = (float) Math.sin(Math.PI / n * (i + 0.5));
        return window;
    }

    private static float[] kbd(int n, double alpha) {
        int half = n / 2;
        double[] kaiser = new double[half + 1];
        double sum = 0;
        for (int j = 0; j <= half; j++) {
            double x = (j - n / 4.0) / (n / 4.0);
            kaiser[j] = bessel(Math.PI * alpha * Math.sqrt(Math.max(0, 1 - x * x)));
            sum += kaiser[j];
        }
        float[] window = new float[n];
        double running = 0;
        for (int i = 0; i < half; i++) {
            running += kaiser[i];
            window[i] = (float) Math.sqrt(running / sum);
            window[n - 1 - i] = window[i];
        }
        return window;
    }

    private static double bessel(double x) {
        double sum = 1;
        double term = 1;
        for (int k = 1; k < 50; k++) {
            term *= (x / (2 * k)) * (x / (2 * k));
            sum += term;
            if (term < sum * 1e-12) break;
        }
        return sum;
    }
}
