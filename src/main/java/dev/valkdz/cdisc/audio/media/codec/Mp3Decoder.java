package dev.valkdz.cdisc.audio.media.codec;

import dev.valkdz.cdisc.audio.media.AudioDecoder;
import dev.valkdz.cdisc.audio.media.Packet;

import java.util.Arrays;

// A float port of minimp3 (CC0, https://github.com/lieff/minimp3): MPEG-1/2/2.5 layers I, II and III.
public final class Mp3Decoder implements AudioDecoder {

    private static final int MAX_BITRESERVOIR_BYTES = 511;
    private static final int MAX_L3_FRAME_PAYLOAD_BYTES = 2304;
    private static final int SHORT_BLOCK_TYPE = 2;
    private static final int STOP_BLOCK_TYPE = 3;
    private static final int MODE_MONO = 3;
    private static final int MODE_JOINT_STEREO = 1;
    private static final int HDR_SIZE = 4;
    private static final int BITS_DEQUANTIZER_OUT = -1;
    private static final int MAX_SCF = 255 + BITS_DEQUANTIZER_OUT * 4 - 210;
    private static final int MAX_SCFI = (MAX_SCF + 3) & ~3;

    private final int sampleRate;
    private final int channels;

    private final float[][] mdctOverlap = new float[2][9 * 32];
    private final float[] qmfState = new float[15 * 2 * 32];
    private int reserv;
    private final byte[] reservBuf = new byte[MAX_BITRESERVOIR_BYTES];
    private final int[] header = new int[4];
    private boolean haveHeader;

    private final Bits frameBits = new Bits();
    private final Bits mainBits = new Bits();
    private final byte[] maindata = new byte[MAX_BITRESERVOIR_BYTES + MAX_L3_FRAME_PAYLOAD_BYTES + 16];
    private final GrInfo[] grInfo = {new GrInfo(), new GrInfo(), new GrInfo(), new GrInfo()};
    private final float[] grbuf = new float[2 * 576];
    private final float[] scf = new float[40];
    private final float[] syn = new float[(18 + 15) * 2 * 32];
    private final int[][] istPos = new int[2][39];
    private final float[] pcm = new float[1152 * 2];
    private final ScaleInfo scaleInfo = new ScaleInfo();

    public Mp3Decoder(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int maxSamples() {
        return 1152;
    }

    @Override
    public void reset() {
        for (float[] overlap : mdctOverlap) Arrays.fill(overlap, 0);
        Arrays.fill(qmfState, 0);
        reserv = 0;
        haveHeader = false;
    }

    @Override
    public int decode(Packet packet, float[][] out) {
        byte[] data = packet.data();
        int at = packet.offset();
        int length = packet.length();
        if (length < HDR_SIZE) return 0;

        int[] h = {data[at] & 0xFF, data[at + 1] & 0xFF, data[at + 2] & 0xFF, data[at + 3] & 0xFF};
        if (!hdrValid(h)) return 0;
        if (haveHeader && !hdrCompare(header, h)) reset();
        System.arraycopy(h, 0, header, 0, 4);
        haveHeader = true;

        int nch = isMono(h) ? 1 : 2;
        int samples = decodeFrame(data, at, length, h, nch);
        int written = Math.min(samples, out[0].length);
        for (int i = 0; i < written; i++) {
            float left = pcm[i * nch];
            float right = nch == 2 ? pcm[i * nch + 1] : left;
            out[0][i] = left;
            if (out.length > 1) out[1][i] = channels == 1 ? left : right;
        }
        return written;
    }

    private int decodeFrame(byte[] data, int at, int length, int[] h, int nch) {
        frameBits.init(data, at + HDR_SIZE, length - HDR_SIZE);
        if (isCrc(h)) frameBits.get(16);

        int layer = 4 - getLayer(h);
        int out = 0;
        if (layer == 3) {
            int mainDataBegin = readSideInfo(frameBits, h);
            if (mainDataBegin < 0 || frameBits.pos > frameBits.limit) {
                reset();
                return 0;
            }
            boolean success = restoreReservoir(mainDataBegin);
            if (success) {
                int granules = testMpeg1(h) ? 2 : 1;
                for (int igr = 0; igr < granules; igr++) {
                    Arrays.fill(grbuf, 0);
                    l3Decode(h, igr * nch, nch);
                    synthGranule(grbuf, 18, nch, pcm, out * nch);
                    out += 576;
                }
            }
            saveReservoir();
            return success ? frameSamples(h) : 0;
        }

        readScaleInfo(h, frameBits, scaleInfo);
        Arrays.fill(grbuf, 0);
        int i = 0;
        for (int igr = 0; igr < 3; igr++) {
            i += dequantizeGranule(grbuf, i, frameBits, scaleInfo, layer | 1);
            if (i == 12) {
                i = 0;
                applyScf384(scaleInfo, igr, grbuf);
                synthGranule(grbuf, 12, nch, pcm, out * nch);
                Arrays.fill(grbuf, 0);
                out += 384;
            }
            if (frameBits.pos > frameBits.limit) {
                reset();
                return 0;
            }
        }
        return frameSamples(h);
    }

    private static final class Bits {
        byte[] buf;
        int base;
        int pos;
        int limit;

        void init(byte[] data, int offset, int bytes) {
            buf = data;
            base = offset;
            pos = 0;
            limit = bytes * 8;
        }

        int byteAt(int index) {
            int i = base + index;
            return i >= 0 && i < buf.length && index < (limit >> 3) + 8 ? buf[i] & 0xFF : 0;
        }

        int get(int n) {
            int s = pos & 7;
            int shl = n + s;
            int p = pos >> 3;
            if ((pos += n) > limit) return 0;
            int next = byteAt(p++) & (255 >> s);
            int cache = 0;
            while ((shl -= 8) > 0) {
                cache |= next << shl;
                next = byteAt(p++);
            }
            return cache | (next >>> -shl);
        }
    }

    private static final class GrInfo {
        int[] sfbtab;
        int partLength;
        int bigValues;
        int scalefacCompress;
        int globalGain;
        int blockType;
        int mixedBlockFlag;
        int nLongSfb;
        int nShortSfb;
        final int[] tableSelect = new int[3];
        final int[] regionCount = new int[3];
        final int[] subblockGain = new int[3];
        int preflag;
        int scalefacScale;
        int count1Table;
        int scfsi;
    }

    private static final class ScaleInfo {
        final float[] scf = new float[3 * 64];
        int totalBands;
        int stereoBands;
        final int[] bitalloc = new int[64];
        final int[] scfcod = new int[64];
    }

    private static boolean isMono(int[] h) {
        return (h[3] & 0xC0) == 0xC0;
    }

    private static boolean isMsStereo(int[] h) {
        return (h[3] & 0xE0) == 0x60;
    }

    private static boolean isFreeFormat(int[] h) {
        return (h[2] & 0xF0) == 0;
    }

    private static boolean isCrc(int[] h) {
        return (h[1] & 1) == 0;
    }

    private static boolean testPadding(int[] h) {
        return (h[2] & 0x2) != 0;
    }

    private static boolean testMpeg1(int[] h) {
        return (h[1] & 0x8) != 0;
    }

    private static boolean testNotMpeg25(int[] h) {
        return (h[1] & 0x10) != 0;
    }

    private static boolean testIStereo(int[] h) {
        return (h[3] & 0x10) != 0;
    }

    private static boolean testMsStereo(int[] h) {
        return (h[3] & 0x20) != 0;
    }

    private static int getStereoMode(int[] h) {
        return (h[3] >> 6) & 3;
    }

    private static int getStereoModeExt(int[] h) {
        return (h[3] >> 4) & 3;
    }

    private static int getLayer(int[] h) {
        return (h[1] >> 1) & 3;
    }

    private static int getBitrate(int[] h) {
        return h[2] >> 4;
    }

    private static int getSampleRate(int[] h) {
        return (h[2] >> 2) & 3;
    }

    private static int getMySampleRate(int[] h) {
        return getSampleRate(h) + (((h[1] >> 3) & 1) + ((h[1] >> 4) & 1)) * 3;
    }

    private static boolean isFrame576(int[] h) {
        return (h[1] & 14) == 2;
    }

    private static boolean isLayer1(int[] h) {
        return (h[1] & 6) == 6;
    }

    public static boolean hdrValid(int[] h) {
        return h[0] == 0xff
                && ((h[1] & 0xF0) == 0xf0 || (h[1] & 0xFE) == 0xe2)
                && getLayer(h) != 0
                && getBitrate(h) != 15
                && getSampleRate(h) != 3;
    }

    public static boolean hdrCompare(int[] h1, int[] h2) {
        return hdrValid(h2)
                && ((h1[1] ^ h2[1]) & 0xFE) == 0
                && ((h1[2] ^ h2[2]) & 0x0C) == 0
                && !(isFreeFormat(h1) ^ isFreeFormat(h2));
    }

    private static final int[][][] HALFRATE = {
            {{0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80}, {0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80}, {0, 16, 24, 28, 32, 40, 48, 56, 64, 72, 80, 88, 96, 112, 128}},
            {{0, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160}, {0, 16, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192}, {0, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224}},
    };

    public static int bitrateKbps(int[] h) {
        return 2 * HALFRATE[testMpeg1(h) ? 1 : 0][getLayer(h) - 1][getBitrate(h)];
    }

    public static int sampleRateHz(int[] h) {
        int[] hz = {44100, 48000, 32000};
        return hz[getSampleRate(h)] >> (testMpeg1(h) ? 0 : 1) >> (testNotMpeg25(h) ? 0 : 1);
    }

    public static int frameSamples(int[] h) {
        return isLayer1(h) ? 384 : (1152 >> (isFrame576(h) ? 1 : 0));
    }

    public static int frameBytes(int[] h, int freeFormatSize) {
        int bytes = frameSamples(h) * bitrateKbps(h) * 125 / sampleRateHz(h);
        if (isLayer1(h)) bytes &= ~3;
        return bytes != 0 ? bytes : freeFormatSize;
    }

    public static int padding(int[] h) {
        return testPadding(h) ? (isLayer1(h) ? 4 : 1) : 0;
    }

    public static int channelsOf(int[] h) {
        return isMono(h) ? 1 : 2;
    }

    private static final int[][] ALLOC_L1 = {{76, 4, 32}};
    private static final int[][] ALLOC_L2M2 = {{60, 4, 4}, {44, 3, 7}, {44, 2, 19}};
    private static final int[][] ALLOC_L2M1 = {{0, 4, 3}, {16, 4, 8}, {32, 3, 12}, {40, 2, 7}};
    private static final int[][] ALLOC_L2M1_LOWRATE = {{44, 4, 2}, {44, 3, 10}};

    private static int[][] subbandAllocTable(int[] h, ScaleInfo sci) {
        int[][] alloc;
        int mode = getStereoMode(h);
        int nbands;
        int stereoBands = mode == MODE_MONO ? 0 : mode == MODE_JOINT_STEREO ? (getStereoModeExt(h) << 2) + 4 : 32;

        if (isLayer1(h)) {
            alloc = ALLOC_L1;
            nbands = 32;
        } else if (!testMpeg1(h)) {
            alloc = ALLOC_L2M2;
            nbands = 30;
        } else {
            int sampleRateIdx = getSampleRate(h);
            int kbps = bitrateKbps(h) >> (mode != MODE_MONO ? 1 : 0);
            if (kbps == 0) kbps = 192;
            alloc = ALLOC_L2M1;
            nbands = 27;
            if (kbps < 56) {
                alloc = ALLOC_L2M1_LOWRATE;
                nbands = sampleRateIdx == 2 ? 12 : 8;
            } else if (kbps >= 96 && sampleRateIdx != 1) {
                nbands = 30;
            }
        }
        sci.totalBands = nbands;
        sci.stereoBands = Math.min(stereoBands, nbands);
        return alloc;
    }

    private static final float[] DEQ_L12 = deqL12();

    private static float[] deqL12() {
        int[] divisors = {3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535, 3, 5, 9};
        float[] table = new float[18 * 3];
        for (int i = 0; i < 18; i++) {
            table[i * 3] = 9.53674316e-07f / divisors[i];
            table[i * 3 + 1] = 7.56931807e-07f / divisors[i];
            table[i * 3 + 2] = 6.00777173e-07f / divisors[i];
        }
        return table;
    }

    private static void readScalefactors(Bits bs, int[] pba, int[] scfcod, int bands, float[] scf) {
        int s = 0;
        for (int i = 0; i < bands; i++) {
            float value = 0;
            int ba = pba[i];
            int mask = ba != 0 ? 4 + ((19 >> scfcod[i]) & 3) : 0;
            for (int m = 4; m != 0; m >>= 1) {
                if ((mask & m) != 0) {
                    int b = bs.get(6);
                    value = DEQ_L12[ba * 3 - 6 + b % 3] * (1 << 21 >> b / 3);
                }
                scf[s++] = value;
            }
        }
    }

    private static final int[] BITALLOC_CODE_TAB = {
            0, 17, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            0, 17, 18, 3, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16,
            0, 17, 18, 3, 19, 4, 5, 16,
            0, 17, 18, 16,
            0, 17, 18, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            0, 17, 18, 3, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };

    private static void readScaleInfo(int[] h, Bits bs, ScaleInfo sci) {
        int[][] subbandAlloc = subbandAllocTable(h, sci);
        int alloc = 0;
        int k = 0;
        int baBits = 0;
        int baCodeTab = 0;

        for (int i = 0; i < sci.totalBands; i++) {
            if (i == k) {
                k += subbandAlloc[alloc][2];
                baBits = subbandAlloc[alloc][1];
                baCodeTab = subbandAlloc[alloc][0];
                alloc++;
            }
            int ba = BITALLOC_CODE_TAB[baCodeTab + bs.get(baBits)];
            sci.bitalloc[2 * i] = ba;
            if (i < sci.stereoBands) ba = BITALLOC_CODE_TAB[baCodeTab + bs.get(baBits)];
            sci.bitalloc[2 * i + 1] = sci.stereoBands != 0 ? ba : 0;
        }

        for (int i = 0; i < 2 * sci.totalBands; i++) {
            sci.scfcod[i] = sci.bitalloc[i] != 0 ? (isLayer1(h) ? 2 : bs.get(2)) : 6;
        }

        readScalefactors(bs, sci.bitalloc, sci.scfcod, sci.totalBands * 2, sci.scf);

        for (int i = sci.stereoBands; i < sci.totalBands; i++) sci.bitalloc[2 * i + 1] = 0;
    }

    private static int dequantizeGranule(float[] grbuf, int base, Bits bs, ScaleInfo sci, int groupSize) {
        int choff = 576;
        for (int j = 0; j < 4; j++) {
            int dst = base + groupSize * j;
            for (int i = 0; i < 2 * sci.totalBands; i++) {
                int ba = sci.bitalloc[i];
                if (ba != 0) {
                    if (ba < 17) {
                        int half = (1 << (ba - 1)) - 1;
                        for (int k = 0; k < groupSize; k++) grbuf[dst + k] = bs.get(ba) - half;
                    } else {
                        int mod = (2 << (ba - 17)) + 1;
                        int code = bs.get(mod + 2 - (mod >> 3));
                        for (int k = 0; k < groupSize; k++, code /= mod) {
                            grbuf[dst + k] = code % mod - mod / 2;
                        }
                    }
                }
                dst += choff;
                choff = 18 - choff;
            }
        }
        return groupSize * 4;
    }

    private static void applyScf384(ScaleInfo sci, int scfOffset, float[] dst) {
        System.arraycopy(dst, sci.stereoBands * 18, dst, 576 + sci.stereoBands * 18,
                (sci.totalBands - sci.stereoBands) * 18);
        int d = 0;
        int s = scfOffset;
        for (int i = 0; i < sci.totalBands; i++, d += 18, s += 6) {
            for (int k = 0; k < 12; k++) {
                dst[d + k] *= sci.scf[s];
                dst[d + k + 576] *= sci.scf[s + 3];
            }
        }
    }

    private static final int[][] SCF_LONG = {
            {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 38, 46, 52, 60, 68, 58, 54, 0},
            {12, 12, 12, 12, 12, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 76, 90, 2, 2, 2, 2, 2, 0},
            {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 38, 46, 52, 60, 68, 58, 54, 0},
            {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 18, 22, 26, 32, 38, 46, 54, 62, 70, 76, 36, 0},
            {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 38, 46, 52, 60, 68, 58, 54, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 8, 8, 10, 12, 16, 20, 24, 28, 34, 42, 50, 54, 76, 158, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 10, 12, 16, 18, 22, 28, 34, 40, 46, 54, 54, 192, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 8, 10, 12, 16, 20, 24, 30, 38, 46, 56, 68, 84, 102, 26, 0}
    };
    private static final int[][] SCF_SHORT = {
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 30, 30, 30, 40, 40, 40, 18, 18, 18, 0},
            {8, 8, 8, 8, 8, 8, 8, 8, 8, 12, 12, 12, 16, 16, 16, 20, 20, 20, 24, 24, 24, 28, 28, 28, 36, 36, 36, 2, 2, 2, 2, 2, 2, 2, 2, 2, 26, 26, 26, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 6, 6, 6, 8, 8, 8, 10, 10, 10, 14, 14, 14, 18, 18, 18, 26, 26, 26, 32, 32, 32, 42, 42, 42, 18, 18, 18, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 32, 32, 32, 44, 44, 44, 12, 12, 12, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 30, 30, 30, 40, 40, 40, 18, 18, 18, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 22, 22, 22, 30, 30, 30, 56, 56, 56, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 6, 6, 6, 10, 10, 10, 12, 12, 12, 14, 14, 14, 16, 16, 16, 20, 20, 20, 26, 26, 26, 66, 66, 66, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 8, 8, 12, 12, 12, 16, 16, 16, 20, 20, 20, 26, 26, 26, 34, 34, 34, 42, 42, 42, 12, 12, 12, 0}
    };
    private static final int[][] SCF_MIXED = {
            {6, 6, 6, 6, 6, 6, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 30, 30, 30, 40, 40, 40, 18, 18, 18, 0},
            {12, 12, 12, 4, 4, 4, 8, 8, 8, 12, 12, 12, 16, 16, 16, 20, 20, 20, 24, 24, 24, 28, 28, 28, 36, 36, 36, 2, 2, 2, 2, 2, 2, 2, 2, 2, 26, 26, 26, 0},
            {6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 8, 8, 8, 10, 10, 10, 14, 14, 14, 18, 18, 18, 26, 26, 26, 32, 32, 32, 42, 42, 42, 18, 18, 18, 0},
            {6, 6, 6, 6, 6, 6, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 32, 32, 32, 44, 44, 44, 12, 12, 12, 0},
            {6, 6, 6, 6, 6, 6, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 30, 30, 30, 40, 40, 40, 18, 18, 18, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 4, 4, 4, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 22, 22, 22, 30, 30, 30, 56, 56, 56, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 4, 4, 4, 6, 6, 6, 6, 6, 6, 10, 10, 10, 12, 12, 12, 14, 14, 14, 16, 16, 16, 20, 20, 20, 26, 26, 26, 66, 66, 66, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 4, 4, 4, 6, 6, 6, 8, 8, 8, 12, 12, 12, 16, 16, 16, 20, 20, 20, 26, 26, 26, 34, 34, 34, 42, 42, 42, 12, 12, 12, 0}
    };

    private int readSideInfo(Bits bs, int[] h) {
        int scfsi = 0;
        int mainDataBegin;
        int partSum = 0;
        int srIdx = getMySampleRate(h);
        srIdx -= srIdx != 0 ? 1 : 0;
        int grCount = isMono(h) ? 1 : 2;

        if (testMpeg1(h)) {
            grCount *= 2;
            mainDataBegin = bs.get(9);
            scfsi = bs.get(7 + grCount);
        } else {
            mainDataBegin = bs.get(8 + grCount) >> grCount;
        }

        int g = 0;
        do {
            GrInfo gr = grInfo[g];
            if (isMono(h)) scfsi <<= 4;
            gr.partLength = bs.get(12);
            partSum += gr.partLength;
            gr.bigValues = bs.get(9);
            if (gr.bigValues > 288) return -1;
            gr.globalGain = bs.get(8);
            gr.scalefacCompress = bs.get(testMpeg1(h) ? 4 : 9);
            gr.sfbtab = SCF_LONG[srIdx];
            gr.nLongSfb = 22;
            gr.nShortSfb = 0;
            int tables;
            if (bs.get(1) != 0) {
                gr.blockType = bs.get(2);
                if (gr.blockType == 0) return -1;
                gr.mixedBlockFlag = bs.get(1);
                gr.regionCount[0] = 7;
                gr.regionCount[1] = 255;
                if (gr.blockType == SHORT_BLOCK_TYPE) {
                    scfsi &= 0x0F0F;
                    if (gr.mixedBlockFlag == 0) {
                        gr.regionCount[0] = 8;
                        gr.sfbtab = SCF_SHORT[srIdx];
                        gr.nLongSfb = 0;
                        gr.nShortSfb = 39;
                    } else {
                        gr.sfbtab = SCF_MIXED[srIdx];
                        gr.nLongSfb = testMpeg1(h) ? 8 : 6;
                        gr.nShortSfb = 30;
                    }
                }
                tables = bs.get(10);
                tables <<= 5;
                gr.subblockGain[0] = bs.get(3);
                gr.subblockGain[1] = bs.get(3);
                gr.subblockGain[2] = bs.get(3);
            } else {
                gr.blockType = 0;
                gr.mixedBlockFlag = 0;
                tables = bs.get(15);
                gr.regionCount[0] = bs.get(4);
                gr.regionCount[1] = bs.get(3);
                gr.regionCount[2] = 255;
            }
            gr.tableSelect[0] = tables >> 10;
            gr.tableSelect[1] = (tables >> 5) & 31;
            gr.tableSelect[2] = tables & 31;
            gr.preflag = testMpeg1(h) ? bs.get(1) : (gr.scalefacCompress >= 500 ? 1 : 0);
            gr.scalefacScale = bs.get(1);
            gr.count1Table = bs.get(1);
            gr.scfsi = (scfsi >> 12) & 15;
            scfsi <<= 4;
            g++;
        } while (--grCount != 0);

        if (partSum + bs.pos > bs.limit + mainDataBegin * 8) return -1;
        return mainDataBegin;
    }

    private static void l3ReadScalefactors(int[] scf, int[] istPos, int[] scfSize, int[] scfCount, int scfCountAt,
                                           Bits bitbuf, int scfsi) {
        int s = 0;
        for (int i = 0; i < 4 && scfCount[scfCountAt + i] != 0; i++, scfsi *= 2) {
            int cnt = scfCount[scfCountAt + i];
            if ((scfsi & 8) != 0) {
                System.arraycopy(istPos, s, scf, s, cnt);
            } else {
                int bits = scfSize[i];
                if (bits == 0) {
                    Arrays.fill(scf, s, s + cnt, 0);
                    Arrays.fill(istPos, s, s + cnt, 0);
                } else {
                    int maxScf = scfsi < 0 ? (1 << bits) - 1 : -1;
                    for (int k = 0; k < cnt; k++) {
                        int v = bitbuf.get(bits);
                        istPos[s + k] = v == maxScf ? 255 : v;
                        scf[s + k] = v;
                    }
                }
            }
            s += cnt;
        }
        scf[s] = scf[s + 1] = scf[s + 2] = 0;
    }

    private static final float[] EXPFRAC = {9.31322575e-10f, 7.83145814e-10f, 6.58544508e-10f, 5.53767716e-10f};

    private static float ldexpQ2(float y, int expQ2) {
        int e;
        do {
            e = Math.min(30 * 4, expQ2);
            y *= EXPFRAC[e & 3] * (1 << 30 >> (e >> 2));
        } while ((expQ2 -= e) > 0);
        return y;
    }

    private static final int[][] SCF_PARTITIONS = {
            {6, 5, 5, 5, 6, 5, 5, 5, 6, 5, 7, 3, 11, 10, 0, 0, 7, 7, 7, 0, 6, 6, 6, 3, 8, 8, 5, 0},
            {8, 9, 6, 12, 6, 9, 9, 9, 6, 9, 12, 6, 15, 18, 0, 0, 6, 15, 12, 0, 6, 12, 9, 6, 6, 18, 9, 0},
            {9, 9, 6, 12, 9, 9, 9, 9, 9, 9, 12, 6, 18, 18, 0, 0, 12, 12, 12, 0, 12, 9, 9, 6, 15, 12, 9, 0}
    };
    private static final int[] SCFC_DECODE = {0, 1, 2, 3, 12, 5, 6, 7, 9, 10, 11, 13, 14, 15, 18, 19};
    private static final int[] MOD = {5, 5, 4, 4, 5, 5, 4, 1, 4, 3, 1, 1, 5, 6, 6, 1, 4, 4, 4, 1, 4, 3, 1, 1};
    private static final int[] PREAMP = {1, 1, 1, 1, 2, 2, 3, 3, 3, 2};

    private final int[] iscf = new int[40];
    private final int[] scfSize = new int[4];

    private void decodeScalefactors(int[] h, int[] ist, Bits bs, GrInfo gr, float[] out, int ch) {
        int[] partition = SCF_PARTITIONS[(gr.nShortSfb != 0 ? 1 : 0) + (gr.nLongSfb == 0 ? 1 : 0)];
        int partitionAt = 0;
        int scfShift = gr.scalefacScale + 1;
        int scfsi = gr.scfsi;

        if (testMpeg1(h)) {
            int part = SCFC_DECODE[gr.scalefacCompress];
            scfSize[1] = scfSize[0] = part >> 2;
            scfSize[3] = scfSize[2] = part & 3;
        } else {
            int ist0 = testIStereo(h) && ch != 0 ? 1 : 0;
            int sfc = gr.scalefacCompress >> ist0;
            int k;
            int modprod;
            for (k = ist0 * 3 * 4; sfc >= 0; sfc -= modprod, k += 4) {
                modprod = 1;
                for (int i = 3; i >= 0; i--) {
                    scfSize[i] = sfc / modprod % MOD[k + i];
                    modprod *= MOD[k + i];
                }
            }
            partitionAt = k;
            scfsi = -16;
        }
        l3ReadScalefactors(iscf, ist, scfSize, partition, partitionAt, bs, scfsi);

        if (gr.nShortSfb != 0) {
            int sh = 3 - scfShift;
            for (int i = 0; i < gr.nShortSfb; i += 3) {
                iscf[gr.nLongSfb + i] = (iscf[gr.nLongSfb + i] + (gr.subblockGain[0] << sh)) & 0xFF;
                iscf[gr.nLongSfb + i + 1] = (iscf[gr.nLongSfb + i + 1] + (gr.subblockGain[1] << sh)) & 0xFF;
                iscf[gr.nLongSfb + i + 2] = (iscf[gr.nLongSfb + i + 2] + (gr.subblockGain[2] << sh)) & 0xFF;
            }
        } else if (gr.preflag != 0) {
            for (int i = 0; i < 10; i++) iscf[11 + i] = (iscf[11 + i] + PREAMP[i]) & 0xFF;
        }

        int gainExp = gr.globalGain + BITS_DEQUANTIZER_OUT * 4 - 210 - (isMsStereo(h) ? 2 : 0);
        float gain = ldexpQ2(1 << (MAX_SCFI / 4), MAX_SCFI - gainExp);
        for (int i = 0; i < gr.nLongSfb + gr.nShortSfb; i++) {
            out[i] = ldexpQ2(gain, iscf[i] << scfShift);
        }
    }

    private static final float[] POW43 = {
            0, -1, -2.519842f, -4.326749f, -6.349604f, -8.549880f, -10.902724f, -13.390518f, -16.000000f, -18.720754f, -21.544347f, -24.463781f, -27.473142f, -30.567351f, -33.741992f, -36.993181f,
            0, 1, 2.519842f, 4.326749f, 6.349604f, 8.549880f, 10.902724f, 13.390518f, 16.000000f, 18.720754f, 21.544347f, 24.463781f, 27.473142f, 30.567351f, 33.741992f, 36.993181f, 40.317474f, 43.711787f, 47.173345f, 50.699631f, 54.288352f, 57.937408f, 61.644865f, 65.408941f, 69.227979f, 73.100443f, 77.024898f, 81.000000f, 85.024491f, 89.097188f, 93.216975f, 97.382800f, 101.593667f, 105.848633f, 110.146801f, 114.487321f, 118.869381f, 123.292209f, 127.755065f, 132.257246f, 136.798076f, 141.376907f, 145.993119f, 150.646117f, 155.335327f, 160.060199f, 164.820202f, 169.614826f, 174.443577f, 179.305980f, 184.201575f, 189.129918f, 194.090580f, 199.083145f, 204.107210f, 209.162385f, 214.248292f, 219.364564f, 224.510845f, 229.686789f, 234.892058f, 240.126328f, 245.389280f, 250.680604f, 256.000000f, 261.347174f, 266.721841f, 272.123723f, 277.552547f, 283.008049f, 288.489971f, 293.998060f, 299.532071f, 305.091761f, 310.676898f, 316.287249f, 321.922592f, 327.582707f, 333.267377f, 338.976394f, 344.709550f, 350.466646f, 356.247482f, 362.051866f, 367.879608f, 373.730522f, 379.604427f, 385.501143f, 391.420496f, 397.362314f, 403.326427f, 409.312672f, 415.320884f, 421.350905f, 427.402579f, 433.475750f, 439.570269f, 445.685987f, 451.822757f, 457.980436f, 464.158883f, 470.357960f, 476.577530f, 482.817459f, 489.077615f, 495.357868f, 501.658090f, 507.978156f, 514.317941f, 520.677324f, 527.056184f, 533.454404f, 539.871867f, 546.308458f, 552.764065f, 559.238575f, 565.731879f, 572.243870f, 578.774440f, 585.323483f, 591.890898f, 598.476581f, 605.080431f, 611.702349f, 618.342238f, 625.000000f, 631.675540f, 638.368763f, 645.079578f
    };

    private static float pow43(int x) {
        int mult = 256;
        if (x < 129) return POW43[16 + x];
        if (x < 1024) {
            mult = 16;
            x <<= 3;
        }
        int sign = 2 * x & 64;
        float frac = (float) ((x & 63) - sign) / ((x & ~63) + sign);
        return POW43[16 + ((x + sign) >> 6)] * (1.f + frac * ((4.f / 3) + frac * (2.f / 9))) * mult;
    }

    private static final short[] TABS = Mp3Tables.TABS;
    private static final int[] TAB32 = {130, 162, 193, 209, 44, 28, 76, 140, 9, 9, 9, 9, 9, 9, 9, 9, 190, 254, 222, 238, 126, 94, 157, 157, 109, 61, 173, 205};
    private static final int[] TAB33 = {252, 236, 220, 204, 188, 172, 156, 140, 124, 108, 92, 76, 60, 44, 28, 12};
    private static final int[] TABINDEX = {0, 32, 64, 98, 0, 132, 180, 218, 292, 364, 426, 538, 648, 746, 0, 1126, 1460, 1460, 1460, 1460, 1460, 1460, 1460, 1460, 1842, 1842, 1842, 1842, 1842, 1842, 1842, 1842};
    private static final int[] LINBITS = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 6, 8, 10, 13, 4, 5, 6, 7, 8, 9, 11, 13};

    private int cache;
    private int sh;
    private int nextPtr;
    private Bits hbs;

    private int peek(int n) {
        return cache >>> (32 - n);
    }

    private void flush(int n) {
        cache <<= n;
        sh += n;
    }

    private void check() {
        while (sh >= 0) {
            cache |= hbs.byteAt(nextPtr++) << sh;
            sh -= 8;
        }
    }

    private void huffman(float[] dst, int d, Bits bs, GrInfo gr, float[] scf, int limit) {
        float one = 0.0f;
        int ireg = 0;
        int bigValCnt = gr.bigValues;
        int[] sfbTab = gr.sfbtab;
        int sfb = 0;
        int scfAt = 0;
        hbs = bs;
        nextPtr = bs.pos / 8;
        cache = ((bs.byteAt(nextPtr) << 24) | (bs.byteAt(nextPtr + 1) << 16)
                | (bs.byteAt(nextPtr + 2) << 8) | bs.byteAt(nextPtr + 3)) << (bs.pos & 7);
        sh = (bs.pos & 7) - 8;
        nextPtr += 4;

        while (bigValCnt > 0) {
            int tabNum = gr.tableSelect[ireg];
            int sfbCnt = gr.regionCount[ireg++];
            int codebook = TABINDEX[tabNum];
            int linbits = LINBITS[tabNum];
            int np;
            do {
                np = sfbTab[sfb++] / 2;
                int pairs = Math.min(bigValCnt, np);
                one = scf[scfAt++];
                do {
                    int w = 5;
                    int leaf = TABS[codebook + peek(w)];
                    while (leaf < 0) {
                        flush(w);
                        w = leaf & 7;
                        leaf = TABS[codebook + peek(w) - (leaf >> 3)];
                    }
                    flush(leaf >> 8);

                    for (int j = 0; j < 2; j++, d++, leaf >>= 4) {
                        int lsb = leaf & 0x0F;
                        if (linbits != 0 && lsb == 15) {
                            lsb += peek(linbits);
                            flush(linbits);
                            check();
                            dst[d] = one * pow43(lsb) * (cache < 0 ? -1 : 1);
                        } else {
                            dst[d] = POW43[16 + lsb - 16 * (cache >>> 31)] * one;
                        }
                        flush(lsb != 0 ? 1 : 0);
                    }
                    check();
                } while (--pairs != 0);
            } while ((bigValCnt -= np) > 0 && --sfbCnt >= 0);
        }

        for (int np = 1 - bigValCnt; ; d += 4) {
            int[] count1 = gr.count1Table != 0 ? TAB33 : TAB32;
            int leaf = count1[peek(4)];
            if ((leaf & 8) == 0) {
                leaf = count1[(leaf >> 3) + ((cache << 4) >>> (32 - (leaf & 3)))];
            }
            flush(leaf & 7);
            if (nextPtr * 8 - 24 + sh > limit) break;

            if (--np == 0) {
                np = sfbTab[sfb++] / 2;
                if (np == 0) break;
                one = scf[scfAt++];
            }
            if ((leaf & 128) != 0) {
                dst[d] = cache < 0 ? -one : one;
                flush(1);
            }
            if ((leaf & 64) != 0) {
                dst[d + 1] = cache < 0 ? -one : one;
                flush(1);
            }
            if (--np == 0) {
                np = sfbTab[sfb++] / 2;
                if (np == 0) break;
                one = scf[scfAt++];
            }
            if ((leaf & 32) != 0) {
                dst[d + 2] = cache < 0 ? -one : one;
                flush(1);
            }
            if ((leaf & 16) != 0) {
                dst[d + 3] = cache < 0 ? -one : one;
                flush(1);
            }
            check();
        }

        bs.pos = limit;
    }

    private static void midsideStereo(float[] buf, int left, int n) {
        int right = left + 576;
        for (int i = 0; i < n; i++) {
            float a = buf[left + i];
            float b = buf[right + i];
            buf[left + i] = a + b;
            buf[right + i] = a - b;
        }
    }

    private static void intensityStereoBand(float[] buf, int left, int n, float kl, float kr) {
        for (int i = 0; i < n; i++) {
            buf[left + i + 576] = buf[left + i] * kr;
            buf[left + i] = buf[left + i] * kl;
        }
    }

    private static void stereoTopBand(float[] buf, int right, int[] sfb, int nbands, int[] maxBand) {
        maxBand[0] = maxBand[1] = maxBand[2] = -1;
        for (int i = 0; i < nbands; i++) {
            for (int k = 0; k < sfb[i]; k += 2) {
                if (buf[right + k] != 0 || buf[right + k + 1] != 0) {
                    maxBand[i % 3] = i;
                    break;
                }
            }
            right += sfb[i];
        }
    }

    private static final float[] PAN = {0, 1, 0.21132487f, 0.78867513f, 0.36602540f, 0.63397460f, 0.5f, 0.5f, 0.63397460f, 0.36602540f, 0.78867513f, 0.21132487f, 1, 0};

    private static void stereoProcess(float[] buf, int left, int[] ist, int[] sfb, int[] h, int[] maxBand, int mpeg2Sh) {
        int maxPos = testMpeg1(h) ? 7 : 64;
        for (int i = 0; sfb[i] != 0; i++) {
            int ipos = ist[i];
            if (i > maxBand[i % 3] && ipos < maxPos) {
                float kl;
                float kr;
                float s = testMsStereo(h) ? 1.41421356f : 1;
                if (testMpeg1(h)) {
                    kl = PAN[2 * ipos];
                    kr = PAN[2 * ipos + 1];
                } else {
                    kl = 1;
                    kr = ldexpQ2(1, (ipos + 1) >> 1 << mpeg2Sh);
                    if ((ipos & 1) != 0) {
                        kl = kr;
                        kr = 1;
                    }
                }
                intensityStereoBand(buf, left, sfb[i], kl * s, kr * s);
            } else if (testMsStereo(h)) {
                midsideStereo(buf, left, sfb[i]);
            }
            left += sfb[i];
        }
    }

    private final int[] maxBand = new int[3];

    private void intensityStereo(float[] buf, int[] ist, int g, int[] h) {
        GrInfo gr = grInfo[g];
        int nSfb = gr.nLongSfb + gr.nShortSfb;
        int maxBlocks = gr.nShortSfb != 0 ? 3 : 1;

        stereoTopBand(buf, 576, gr.sfbtab, nSfb, maxBand);
        if (gr.nLongSfb != 0) {
            maxBand[0] = maxBand[1] = maxBand[2] = Math.max(Math.max(maxBand[0], maxBand[1]), maxBand[2]);
        }
        for (int i = 0; i < maxBlocks; i++) {
            int defaultPos = testMpeg1(h) ? 3 : 0;
            int itop = nSfb - maxBlocks + i;
            int prev = itop - maxBlocks;
            ist[itop] = maxBand[i] >= prev ? defaultPos : ist[prev];
        }
        stereoProcess(buf, 0, ist, gr.sfbtab, h, maxBand, grInfo[g + 1].scalefacCompress & 1);
    }

    private static void reorder(float[] buf, int grbuf, float[] scratch, int[] sfb, int sfbAt) {
        int src = grbuf;
        int dst = 0;
        int len;
        for (; (len = sfb[sfbAt]) != 0; sfbAt += 3, src += 2 * len) {
            for (int i = 0; i < len; i++, src++) {
                scratch[dst++] = buf[src];
                scratch[dst++] = buf[src + len];
                scratch[dst++] = buf[src + 2 * len];
            }
        }
        System.arraycopy(scratch, 0, buf, grbuf, dst);
    }

    private static final float[][] AA = {
            {0.85749293f, 0.88174200f, 0.94962865f, 0.98331459f, 0.99551782f, 0.99916056f, 0.99989920f, 0.99999316f},
            {0.51449576f, 0.47173197f, 0.31337745f, 0.18191320f, 0.09457419f, 0.04096558f, 0.01419856f, 0.00369997f}
    };

    private static void antialias(float[] buf, int g, int nbands) {
        for (; nbands > 0; nbands--, g += 18) {
            for (int i = 0; i < 8; i++) {
                float u = buf[g + 18 + i];
                float d = buf[g + 17 - i];
                buf[g + 18 + i] = u * AA[0][i] - d * AA[1][i];
                buf[g + 17 - i] = u * AA[1][i] + d * AA[0][i];
            }
        }
    }

    private static void dct3x9(float[] y) {
        float s0 = y[0], s2 = y[2], s4 = y[4], s6 = y[6], s8 = y[8];
        float t0 = s0 + s6 * 0.5f;
        s0 -= s6;
        float t4 = (s4 + s2) * 0.93969262f;
        float t2 = (s8 + s2) * 0.76604444f;
        s6 = (s4 - s8) * 0.17364818f;
        s4 += s8 - s2;

        s2 = s0 - s4 * 0.5f;
        y[4] = s4 + s0;
        s8 = t0 - t2 + s6;
        s0 = t0 - t4 + t2;
        s4 = t0 + t4 - s6;

        float s1 = y[1], s3 = y[3], s5 = y[5], s7 = y[7];

        s3 *= 0.86602540f;
        t0 = (s5 + s1) * 0.98480775f;
        t4 = (s5 - s7) * 0.34202014f;
        t2 = (s1 + s7) * 0.64278761f;
        s1 = (s1 - s5 - s7) * 0.86602540f;

        s5 = t0 - s3 - t2;
        s7 = t4 - s3 - t0;
        s3 = t4 + s3 - t2;

        y[0] = s4 - s7;
        y[1] = s2 + s1;
        y[2] = s0 - s3;
        y[3] = s8 + s5;
        y[5] = s8 - s5;
        y[6] = s0 + s3;
        y[7] = s2 - s1;
        y[8] = s4 + s7;
    }

    private static final float[] TWID9 = {
            0.73727734f, 0.79335334f, 0.84339145f, 0.88701083f, 0.92387953f, 0.95371695f, 0.97629601f, 0.99144486f, 0.99904822f, 0.67559021f, 0.60876143f, 0.53729961f, 0.46174861f, 0.38268343f, 0.30070580f, 0.21643961f, 0.13052619f, 0.04361938f
    };

    private final float[] co = new float[9];
    private final float[] si = new float[9];

    private void imdct36(float[] buf, int g, float[] overlap, int o, float[] window, int nbands) {
        for (int j = 0; j < nbands; j++, g += 18, o += 9) {
            co[0] = -buf[g];
            si[0] = buf[g + 17];
            for (int i = 0; i < 4; i++) {
                si[8 - 2 * i] = buf[g + 4 * i + 1] - buf[g + 4 * i + 2];
                co[1 + 2 * i] = buf[g + 4 * i + 1] + buf[g + 4 * i + 2];
                si[7 - 2 * i] = buf[g + 4 * i + 4] - buf[g + 4 * i + 3];
                co[2 + 2 * i] = -(buf[g + 4 * i + 3] + buf[g + 4 * i + 4]);
            }
            dct3x9(co);
            dct3x9(si);

            si[1] = -si[1];
            si[3] = -si[3];
            si[5] = -si[5];
            si[7] = -si[7];

            for (int i = 0; i < 9; i++) {
                float ovl = overlap[o + i];
                float sum = co[i] * TWID9[9 + i] + si[i] * TWID9[i];
                overlap[o + i] = co[i] * TWID9[i] - si[i] * TWID9[9 + i];
                buf[g + i] = ovl * window[i] - sum * window[9 + i];
                buf[g + 17 - i] = ovl * window[9 + i] + sum * window[i];
            }
        }
    }

    private static void idct3(float x0, float x1, float x2, float[] dst) {
        float m1 = x1 * 0.86602540f;
        float a1 = x0 - x2 * 0.5f;
        dst[1] = x0 + x2;
        dst[0] = a1 + m1;
        dst[2] = a1 - m1;
    }

    private static final float[] TWID3 = {0.79335334f, 0.92387953f, 0.99144486f, 0.60876143f, 0.38268343f, 0.13052619f};
    private final float[] co3 = new float[3];
    private final float[] si3 = new float[3];

    private void imdct12(float[] x, int xi, float[] dstArr, int dst, float[] overlap, int o) {
        idct3(-x[xi], x[xi + 6] + x[xi + 3], x[xi + 12] + x[xi + 9], co3);
        idct3(x[xi + 15], x[xi + 12] - x[xi + 9], x[xi + 6] - x[xi + 3], si3);
        si3[1] = -si3[1];

        for (int i = 0; i < 3; i++) {
            float ovl = overlap[o + i];
            float sum = co3[i] * TWID3[3 + i] + si3[i] * TWID3[i];
            overlap[o + i] = co3[i] * TWID3[i] - si3[i] * TWID3[3 + i];
            dstArr[dst + i] = ovl * TWID3[2 - i] - sum * TWID3[5 - i];
            dstArr[dst + 5 - i] = ovl * TWID3[5 - i] + sum * TWID3[2 - i];
        }
    }

    private final float[] tmp18 = new float[18];

    private void imdctShort(float[] buf, int g, float[] overlap, int o, int nbands) {
        for (; nbands > 0; nbands--, o += 9, g += 18) {
            System.arraycopy(buf, g, tmp18, 0, 18);
            System.arraycopy(overlap, o, buf, g, 6);
            imdct12(tmp18, 0, buf, g + 6, overlap, o + 6);
            imdct12(tmp18, 1, buf, g + 12, overlap, o + 6);
            imdct12(tmp18, 2, overlap, o, overlap, o + 6);
        }
    }

    private static void changeSign(float[] buf, int g) {
        g += 18;
        for (int b = 0; b < 32; b += 2, g += 36) {
            for (int i = 1; i < 18; i += 2) buf[g + i] = -buf[g + i];
        }
    }

    private static final float[][] MDCT_WINDOW = {
            {0.99904822f, 0.99144486f, 0.97629601f, 0.95371695f, 0.92387953f, 0.88701083f, 0.84339145f, 0.79335334f, 0.73727734f, 0.04361938f, 0.13052619f, 0.21643961f, 0.30070580f, 0.38268343f, 0.46174861f, 0.53729961f, 0.60876143f, 0.67559021f},
            {1, 1, 1, 1, 1, 1, 0.99144486f, 0.92387953f, 0.79335334f, 0, 0, 0, 0, 0, 0, 0.13052619f, 0.38268343f, 0.60876143f}
    };

    private void imdctGr(float[] buf, int g, float[] overlap, int blockType, int nLongBands) {
        int o = 0;
        if (nLongBands != 0) {
            imdct36(buf, g, overlap, o, MDCT_WINDOW[0], nLongBands);
            g += 18 * nLongBands;
            o += 9 * nLongBands;
        }
        if (blockType == SHORT_BLOCK_TYPE) {
            imdctShort(buf, g, overlap, o, 32 - nLongBands);
        } else {
            imdct36(buf, g, overlap, o, MDCT_WINDOW[blockType == STOP_BLOCK_TYPE ? 1 : 0], 32 - nLongBands);
        }
    }

    private void saveReservoir() {
        int pos = (mainBits.pos + 7) / 8;
        int remains = mainBits.limit / 8 - pos;
        if (remains > MAX_BITRESERVOIR_BYTES) {
            pos += remains - MAX_BITRESERVOIR_BYTES;
            remains = MAX_BITRESERVOIR_BYTES;
        }
        if (remains > 0) System.arraycopy(maindata, pos, reservBuf, 0, remains);
        reserv = Math.max(0, remains);
    }

    private boolean restoreReservoir(int mainDataBegin) {
        int frameBytes = (frameBits.limit - frameBits.pos) / 8;
        int bytesHave = Math.min(reserv, mainDataBegin);
        System.arraycopy(reservBuf, Math.max(0, reserv - mainDataBegin), maindata, 0, bytesHave);
        int copy = Math.max(0, Math.min(frameBytes, maindata.length - 16 - bytesHave));
        System.arraycopy(frameBits.buf, frameBits.base + frameBits.pos / 8, maindata, bytesHave, copy);
        Arrays.fill(maindata, bytesHave + copy, Math.min(maindata.length, bytesHave + copy + 16), (byte) 0);
        mainBits.init(maindata, 0, bytesHave + copy);
        return reserv >= mainDataBegin;
    }

    private void l3Decode(int[] h, int g, int nch) {
        for (int ch = 0; ch < nch; ch++) {
            int limit = mainBits.pos + grInfo[g + ch].partLength;
            decodeScalefactors(h, istPos[ch], mainBits, grInfo[g + ch], scf, ch);
            huffman(grbuf, ch * 576, mainBits, grInfo[g + ch], scf, limit);
        }

        if (testIStereo(h)) {
            intensityStereo(grbuf, istPos[1], g, h);
        } else if (isMsStereo(h)) {
            midsideStereo(grbuf, 0, 576);
        }

        for (int ch = 0; ch < nch; ch++) {
            GrInfo gr = grInfo[g + ch];
            int aaBands = 31;
            int nLongBands = (gr.mixedBlockFlag != 0 ? 2 : 0) << (getMySampleRate(h) == 2 ? 1 : 0);

            if (gr.nShortSfb != 0) {
                aaBands = nLongBands - 1;
                reorder(grbuf, ch * 576 + nLongBands * 18, syn, gr.sfbtab, gr.nLongSfb);
            }

            antialias(grbuf, ch * 576, aaBands);
            imdctGr(grbuf, ch * 576, mdctOverlap[ch], gr.blockType, nLongBands);
            changeSign(grbuf, ch * 576);
        }
    }

    private static final float[] SEC = {
            10.19000816f, 0.50060302f, 0.50241929f, 3.40760851f, 0.50547093f, 0.52249861f, 2.05778098f, 0.51544732f, 0.56694406f, 1.48416460f, 0.53104258f, 0.64682180f, 1.16943991f, 0.55310392f, 0.78815460f, 0.97256821f, 0.58293498f, 1.06067765f, 0.83934963f, 0.62250412f, 1.72244716f, 0.74453628f, 0.67480832f, 5.10114861f
    };

    private final float[][] t = new float[4][8];

    private void dctII(float[] buf, int g, int n) {
        for (int k = 0; k < n; k++) {
            int y = g + k;
            for (int i = 0; i < 8; i++) {
                float x0 = buf[y + i * 18];
                float x1 = buf[y + (15 - i) * 18];
                float x2 = buf[y + (16 + i) * 18];
                float x3 = buf[y + (31 - i) * 18];
                float t0 = x0 + x3;
                float t1 = x1 + x2;
                float t2 = (x1 - x2) * SEC[3 * i];
                float t3 = (x0 - x3) * SEC[3 * i + 1];
                t[0][i] = t0 + t1;
                t[1][i] = (t0 - t1) * SEC[3 * i + 2];
                t[2][i] = t3 + t2;
                t[3][i] = (t3 - t2) * SEC[3 * i + 2];
            }
            for (int i = 0; i < 4; i++) {
                float[] x = t[i];
                float x0 = x[0], x1 = x[1], x2 = x[2], x3 = x[3], x4 = x[4], x5 = x[5], x6 = x[6], x7 = x[7], xt;
                xt = x0 - x7;
                x0 += x7;
                x7 = x1 - x6;
                x1 += x6;
                x6 = x2 - x5;
                x2 += x5;
                x5 = x3 - x4;
                x3 += x4;
                x4 = x0 - x3;
                x0 += x3;
                x3 = x1 - x2;
                x1 += x2;
                x[0] = x0 + x1;
                x[4] = (x0 - x1) * 0.70710677f;
                x5 = x5 + x6;
                x6 = (x6 + x7) * 0.70710677f;
                x7 = x7 + xt;
                x3 = (x3 + x4) * 0.70710677f;
                x5 -= x7 * 0.198912367f;
                x7 += x5 * 0.382683432f;
                x5 -= x7 * 0.198912367f;
                x0 = xt - x6;
                xt += x6;
                x[1] = (xt + x7) * 0.50979561f;
                x[2] = (x4 + x3) * 0.54119611f;
                x[3] = (x0 - x5) * 0.60134488f;
                x[5] = (x0 + x5) * 0.89997619f;
                x[6] = (x4 - x3) * 1.30656302f;
                x[7] = (xt - x7) * 2.56291556f;
            }
            for (int i = 0; i < 7; i++, y += 4 * 18) {
                buf[y] = t[0][i];
                buf[y + 18] = t[2][i] + t[3][i] + t[3][i + 1];
                buf[y + 2 * 18] = t[1][i] + t[1][i + 1];
                buf[y + 3 * 18] = t[2][i + 1] + t[3][i] + t[3][i + 1];
            }
            buf[y] = t[0][7];
            buf[y + 18] = t[2][7] + t[3][7];
            buf[y + 2 * 18] = t[1][7];
            buf[y + 3 * 18] = t[3][7];
        }
    }

    private static final float SCALE = 1.0f / 32768.0f;

    private static void synthPair(float[] pcm, int p, int nch, float[] z, int zi) {
        float a;
        a = (z[zi + 14 * 64] - z[zi]) * 29;
        a += (z[zi + 64] + z[zi + 13 * 64]) * 213;
        a += (z[zi + 12 * 64] - z[zi + 2 * 64]) * 459;
        a += (z[zi + 3 * 64] + z[zi + 11 * 64]) * 2037;
        a += (z[zi + 10 * 64] - z[zi + 4 * 64]) * 5153;
        a += (z[zi + 5 * 64] + z[zi + 9 * 64]) * 6574;
        a += (z[zi + 8 * 64] - z[zi + 6 * 64]) * 37489;
        a += z[zi + 7 * 64] * 75038;
        pcm[p] = a * SCALE;

        zi += 2;
        a = z[zi + 14 * 64] * 104;
        a += z[zi + 12 * 64] * 1567;
        a += z[zi + 10 * 64] * 9727;
        a += z[zi + 8 * 64] * 64019;
        a += z[zi + 6 * 64] * -9975;
        a += z[zi + 4 * 64] * -45;
        a += z[zi + 2 * 64] * 146;
        a += z[zi] * -5;
        pcm[p + 16 * nch] = a * SCALE;
    }

    private static final float[] WIN = {
            -1, 26, -31, 208, 218, 401, -519, 2063, 2000, 4788, -5517, 7134, 5959, 35640, -39336, 74992,
            -1, 24, -35, 202, 222, 347, -581, 2080, 1952, 4425, -5879, 7640, 5288, 33791, -41176, 74856,
            -1, 21, -38, 196, 225, 294, -645, 2087, 1893, 4063, -6237, 8092, 4561, 31947, -43006, 74630,
            -1, 19, -41, 190, 227, 244, -711, 2085, 1822, 3705, -6589, 8492, 3776, 30112, -44821, 74313,
            -1, 17, -45, 183, 228, 197, -779, 2075, 1739, 3351, -6935, 8840, 2935, 28289, -46617, 73908,
            -1, 16, -49, 176, 228, 153, -848, 2057, 1644, 3004, -7271, 9139, 2037, 26482, -48390, 73415,
            -2, 14, -53, 169, 227, 111, -919, 2032, 1535, 2663, -7597, 9389, 1082, 24694, -50137, 72835,
            -2, 13, -58, 161, 224, 72, -991, 2001, 1414, 2330, -7910, 9592, 70, 22929, -51853, 72169,
            -2, 11, -63, 154, 221, 36, -1064, 1962, 1280, 2006, -8209, 9750, -998, 21189, -53534, 71420,
            -2, 10, -68, 147, 215, 2, -1137, 1919, 1131, 1692, -8491, 9863, -2122, 19478, -55178, 70590,
            -3, 9, -73, 139, 208, -29, -1210, 1870, 970, 1388, -8755, 9935, -3300, 17799, -56778, 69679,
            -3, 8, -79, 132, 200, -57, -1283, 1817, 794, 1095, -8998, 9966, -4533, 16155, -58333, 68692,
            -4, 7, -85, 125, 189, -83, -1356, 1759, 605, 814, -9219, 9959, -5818, 14548, -59838, 67629,
            -4, 7, -91, 117, 177, -106, -1428, 1698, 402, 545, -9416, 9916, -7154, 12980, -61289, 66494,
            -5, 6, -97, 111, 163, -127, -1498, 1634, 185, 288, -9585, 9838, -8540, 11455, -62684, 65290
    };

    private final float[] a4 = new float[4];
    private final float[] b4 = new float[4];

    private void synth(float[] buf, int xl, float[] pcm, int dstl, int nch, int lins) {
        int xr = xl + 576 * (nch - 1);
        int dstr = dstl + (nch - 1);
        int zlin = lins + 15 * 64;
        float[] z = syn;

        z[zlin + 4 * 15] = buf[xl + 18 * 16];
        z[zlin + 4 * 15 + 1] = buf[xr + 18 * 16];
        z[zlin + 4 * 15 + 2] = buf[xl];
        z[zlin + 4 * 15 + 3] = buf[xr];

        z[zlin + 4 * 31] = buf[xl + 1 + 18 * 16];
        z[zlin + 4 * 31 + 1] = buf[xr + 1 + 18 * 16];
        z[zlin + 4 * 31 + 2] = buf[xl + 1];
        z[zlin + 4 * 31 + 3] = buf[xr + 1];

        synthPair(pcm, dstr, nch, z, lins + 4 * 15 + 1);
        synthPair(pcm, dstr + 32 * nch, nch, z, lins + 4 * 15 + 64 + 1);
        synthPair(pcm, dstl, nch, z, lins + 4 * 15);
        synthPair(pcm, dstl + 32 * nch, nch, z, lins + 4 * 15 + 64);

        for (int i = 14; i >= 0; i--) {
            z[zlin + 4 * i] = buf[xl + 18 * (31 - i)];
            z[zlin + 4 * i + 1] = buf[xr + 18 * (31 - i)];
            z[zlin + 4 * i + 2] = buf[xl + 1 + 18 * (31 - i)];
            z[zlin + 4 * i + 3] = buf[xr + 1 + 18 * (31 - i)];
            z[zlin + 4 * (i + 16)] = buf[xl + 1 + 18 * (1 + i)];
            z[zlin + 4 * (i + 16) + 1] = buf[xr + 1 + 18 * (1 + i)];
            z[zlin + 4 * (i - 16) + 2] = buf[xl + 18 * (1 + i)];
            z[zlin + 4 * (i - 16) + 3] = buf[xr + 18 * (1 + i)];

            for (int k = 0; k < 8; k++) {
                float w0 = WIN[(14 - i) * 16 + 2 * k];
                float w1 = WIN[(14 - i) * 16 + 2 * k + 1];
                int vz = zlin + 4 * i - k * 64;
                int vy = zlin + 4 * i - (15 - k) * 64;
                for (int j = 0; j < 4; j++) {
                    float bz = z[vz + j] * w1 + z[vy + j] * w0;
                    float az = (k & 1) != 0
                            ? z[vy + j] * w1 - z[vz + j] * w0
                            : z[vz + j] * w0 - z[vy + j] * w1;
                    if (k == 0) {
                        b4[j] = bz;
                        a4[j] = az;
                    } else {
                        b4[j] += bz;
                        a4[j] += az;
                    }
                }
            }

            pcm[dstr + (15 - i) * nch] = a4[1] * SCALE;
            pcm[dstr + (17 + i) * nch] = b4[1] * SCALE;
            pcm[dstl + (15 - i) * nch] = a4[0] * SCALE;
            pcm[dstl + (17 + i) * nch] = b4[0] * SCALE;
            pcm[dstr + (47 - i) * nch] = a4[3] * SCALE;
            pcm[dstr + (49 + i) * nch] = b4[3] * SCALE;
            pcm[dstl + (47 - i) * nch] = a4[2] * SCALE;
            pcm[dstl + (49 + i) * nch] = b4[2] * SCALE;
        }
    }

    private void synthGranule(float[] buf, int nbands, int nch, float[] pcm, int p) {
        for (int i = 0; i < nch; i++) dctII(buf, 576 * i, nbands);

        System.arraycopy(qmfState, 0, syn, 0, 15 * 64);

        for (int i = 0; i < nbands; i += 2) {
            synth(buf, i, pcm, p + 32 * nch * i, nch, i * 64);
        }
        if (nch == 1) {
            for (int i = 0; i < 15 * 64; i += 2) qmfState[i] = syn[nbands * 64 + i];
        } else {
            System.arraycopy(syn, nbands * 64, qmfState, 0, 15 * 64);
        }
    }
}
