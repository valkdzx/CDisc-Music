package dev.valkdz.cdisc.audio.media.codec.opus;

import java.util.Arrays;

final class CeltDecoder {

    static final int DECODE_BUFFER_SIZE = 2048;
    private static final int MAX_PERIOD = 1024;
    private static final int LPC_ORDER = 24;
    private static final int COMBFILTER_MINPERIOD = 15;
    private static final int PLC_PITCH_LAG_MAX = 720;
    private static final int PLC_PITCH_LAG_MIN = 100;
    private static final float VERY_SMALL = 1e-30f;
    private static final int BITRES = RangeDecoder.BITRES;
    private static final float[] E_MEANS = {
            6.437500f, 6.250000f, 5.750000f, 5.312500f, 5.062500f,
            4.812500f, 4.500000f, 4.375000f, 4.875000f, 4.687500f,
            4.562500f, 4.437500f, 4.875000f, 4.625000f, 4.312500f,
            4.500000f, 4.375000f, 4.625000f, 4.750000f, 4.437500f,
            3.750000f, 3.750000f, 3.750000f, 3.750000f, 3.750000f
    };
    private static final float[] PRED_COEF = {29440 / 32768f, 26112 / 32768f, 21248 / 32768f, 16384 / 32768f};
    private static final float[] BETA_COEF = {30147 / 32768f, 22282 / 32768f, 12124 / 32768f, 6554 / 32768f};
    private static final float BETA_INTRA = 4915 / 32768f;
    private static final int[][] E_PROB_MODEL = CeltEnergyTables.E_PROB_MODEL;
    private static final int[] SMALL_ENERGY_ICDF = {2, 1, 0};
    private static final int[] TRIM_ICDF = {126, 124, 119, 109, 87, 41, 19, 9, 4, 2, 0};
    private static final int[] SPREAD_ICDF = {25, 23, 2, 0};
    private static final int[] TAPSET_ICDF = {2, 1, 0};
    private static final int[][] TF_SELECT_TABLE = {
            {0, -1, 0, -1, 0, -1, 0, -1},
            {0, -1, 0, -2, 1, 0, 1, -1},
            {0, -2, 0, -3, 2, 0, 1, -1},
            {0, -2, 0, -3, 3, 0, 1, -1},
    };
    private static final float[][] COMB_GAINS = {
            {0.3066406250f, 0.2170410156f, 0.1296386719f},
            {0.4638671875f, 0.2680664062f, 0.f},
            {0.7998046875f, 0.1000976562f, 0.f}};

    private final CeltMode mode = CeltMode.MODE;
    private final Bands bands = new Bands();
    private final RangeDecoder ownDecoder = new RangeDecoder();
    final int channels;
    int streamChannels;
    int start;
    int end;
    final boolean disableInv;

    int rng;
    private int error;
    private int lastPitchIndex;
    private int lossDuration;
    private boolean skipPlc;
    private int postfilterPeriod;
    private int postfilterPeriodOld;
    private float postfilterGain;
    private float postfilterGainOld;
    private int postfilterTapset;
    private int postfilterTapsetOld;
    private boolean prefilterAndFold;
    private final float[] preemphMemD = new float[2];
    final float[][] decodeMem;
    private final float[][] lpc;
    private final float[] oldBandE;
    private final float[] oldLogE;
    private final float[] oldLogE2;
    private final float[] backgroundLogE;

    CeltDecoder(int channels) {
        this.channels = channels;
        this.streamChannels = channels;
        this.start = 0;
        this.end = mode.effEBands;
        this.disableInv = channels == 1;
        int n = mode.nbEBands;
        decodeMem = new float[channels][DECODE_BUFFER_SIZE + mode.overlap];
        lpc = new float[channels][LPC_ORDER];
        oldBandE = new float[2 * n];
        oldLogE = new float[2 * n];
        oldLogE2 = new float[2 * n];
        backgroundLogE = new float[2 * n];
        reset();
    }

    void reset() {
        rng = 0;
        error = 0;
        lastPitchIndex = 0;
        lossDuration = 0;
        postfilterPeriod = 0;
        postfilterPeriodOld = 0;
        postfilterGain = 0;
        postfilterGainOld = 0;
        postfilterTapset = 0;
        postfilterTapsetOld = 0;
        prefilterAndFold = false;
        preemphMemD[0] = preemphMemD[1] = 0;
        for (float[] mem : decodeMem) Arrays.fill(mem, 0);
        for (float[] l : lpc) Arrays.fill(l, 0);
        Arrays.fill(oldBandE, 0);
        Arrays.fill(backgroundLogE, 0);
        Arrays.fill(oldLogE, -28f);
        Arrays.fill(oldLogE2, -28f);
        skipPlc = true;
    }

    // Decodes one CELT frame of frameSize samples at 48 kHz into interleaved pcm; data == null conceals a loss.
    int decode(byte[] data, int offset, int len, float[] pcm, int pcmOffset, int frameSize, RangeDecoder dec) {
        int cc = channels;
        int c2 = streamChannels;
        int nbEBands = mode.nbEBands;
        int overlap = mode.overlap;
        int[] eBands = mode.eBands;
        int lm;
        for (lm = 0; lm <= mode.maxLM; lm++) {
            if (mode.shortMdctSize << lm == frameSize) break;
        }
        if (lm > mode.maxLM) return -1;
        int mm = 1 << lm;
        if (len < 0 || len > 1275) return -1;
        int n = mm * mode.shortMdctSize;
        int outSyn = DECODE_BUFFER_SIZE - n;
        int effEnd = Math.min(end, mode.effEBands);

        if (data == null || len <= 1) {
            decodeLost(n, lm);
            deemphasis(pcm, pcmOffset, n, cc, outSyn);
            return frameSize;
        }

        if (lossDuration == 0) skipPlc = false;
        if (dec == null) {
            ownDecoder.init(data, offset, len);
            dec = ownDecoder;
        }
        if (c2 == 1) {
            for (int i = 0; i < nbEBands; i++) oldBandE[i] = Math.max(oldBandE[i], oldBandE[nbEBands + i]);
        }

        int totalBits = len * 8;
        int tell = dec.tell();
        boolean silence;
        if (tell >= totalBits) silence = true;
        else if (tell == 1) silence = dec.bitLogp(15);
        else silence = false;
        if (silence) {
            tell = len * 8;
            dec.nbitsTotal += tell - dec.tell();
        }

        float postfilterGainNew = 0;
        int postfilterPitch = 0;
        int postfilterTapsetNew = 0;
        if (start == 0 && tell + 16 <= totalBits) {
            if (dec.bitLogp(1)) {
                int octave = (int) dec.uint(6);
                postfilterPitch = (16 << octave) + dec.bits(4 + octave) - 1;
                int qg = dec.bits(3);
                if (dec.tell() + 2 <= totalBits) postfilterTapsetNew = dec.icdf(TAPSET_ICDF, 2);
                postfilterGainNew = .09375f * (qg + 1);
            }
            tell = dec.tell();
        }

        boolean isTransient = false;
        if (lm > 0 && tell + 3 <= totalBits) {
            isTransient = dec.bitLogp(3);
            tell = dec.tell();
        }
        int shortBlocks = isTransient ? mm : 0;

        boolean intraEner = tell + 3 <= totalBits && dec.bitLogp(3);
        if (!intraEner && lossDuration != 0) {
            for (int c = 0; c < 2; c++) {
                float safety = 0;
                int missing = Math.min(10, lossDuration >> lm);
                if (lm == 0) safety = 1.5f;
                else if (lm == 1) safety = .5f;
                for (int i = start; i < end; i++) {
                    int at = c * nbEBands + i;
                    if (oldBandE[at] < Math.max(oldLogE[at], oldLogE2[at])) {
                        float e0 = oldBandE[at];
                        float e1 = oldLogE[at];
                        float e2 = oldLogE2[at];
                        float slope = Math.max(e1 - e0, .5f * (e2 - e0));
                        e0 -= Math.max(0, (1 + missing) * slope);
                        oldBandE[at] = Math.max(-20f, e0);
                    } else {
                        oldBandE[at] = Math.min(Math.min(oldBandE[at], oldLogE[at]), oldLogE2[at]);
                    }
                    oldBandE[at] -= safety;
                }
            }
        }
        unquantCoarseEnergy(start, end, intraEner, dec, c2, lm);

        int[] tfRes = new int[nbEBands];
        tfDecode(start, end, isTransient, tfRes, lm, dec);

        tell = dec.tell();
        int spreadDecision = Bands.SPREAD_NORMAL;
        if (tell + 4 <= totalBits) spreadDecision = dec.icdf(SPREAD_ICDF, 5);

        int[] cap = new int[nbEBands];
        for (int i = 0; i < nbEBands; i++) {
            int width = (eBands[i + 1] - eBands[i]) << lm;
            cap[i] = (mode.cacheCaps[nbEBands * (2 * lm + c2 - 1) + i] + 64) * c2 * width >> 2;
        }

        int[] offsets = new int[nbEBands];
        int dynallocLogp = 6;
        totalBits <<= BITRES;
        tell = dec.tellFrac();
        for (int i = start; i < end; i++) {
            int width = c2 * (eBands[i + 1] - eBands[i]) << lm;
            int quanta = Math.min(width << BITRES, Math.max(6 << BITRES, width));
            int loopLogp = dynallocLogp;
            int boost = 0;
            while (tell + (loopLogp << BITRES) < totalBits && boost < cap[i]) {
                boolean flag = dec.bitLogp(loopLogp);
                tell = dec.tellFrac();
                if (!flag) break;
                boost += quanta;
                totalBits -= quanta;
                loopLogp = 1;
            }
            offsets[i] = boost;
            if (boost > 0) dynallocLogp = Math.max(2, dynallocLogp - 1);
        }

        int[] fineQuant = new int[nbEBands];
        int allocTrim = tell + (6 << BITRES) <= totalBits ? dec.icdf(TRIM_ICDF, 7) : 5;

        int bits = ((len * 8) << BITRES) - dec.tellFrac() - 1;
        int antiCollapseRsv = isTransient && lm >= 2 && bits >= ((lm + 2) << BITRES) ? (1 << BITRES) : 0;
        bits -= antiCollapseRsv;

        int[] pulses = new int[nbEBands];
        int[] finePriority = new int[nbEBands];
        Rate.Allocation alloc = new Rate.Allocation();
        Rate.compute(mode, start, end, offsets, cap, allocTrim, bits, pulses, fineQuant, finePriority, c2, lm, dec, alloc);

        unquantFineEnergy(start, end, fineQuant, dec, c2);

        for (int c = 0; c < cc; c++) {
            System.arraycopy(decodeMem[c], n, decodeMem[c], 0, DECODE_BUFFER_SIZE - n + overlap);
        }

        int[] collapseMasks = new int[c2 * nbEBands];
        float[] x = new float[c2 * n];
        rng = bands.quantAllBands(start, end, x, c2 == 2 ? n : -1, collapseMasks, pulses, shortBlocks,
                spreadDecision, alloc.dualStereo, alloc.intensity, tfRes,
                len * (8 << BITRES) - antiCollapseRsv, alloc.balance, dec, lm, alloc.codedBands, rng, disableInv);

        boolean antiCollapseOn = antiCollapseRsv > 0 && dec.bits(1) != 0;

        unquantEnergyFinalise(start, end, fineQuant, finePriority, len * 8 - dec.tell(), dec, c2);

        if (antiCollapseOn) antiCollapse(x, collapseMasks, lm, c2, n, start, end, pulses, rng);

        if (silence) {
            for (int i = 0; i < c2 * nbEBands; i++) oldBandE[i] = -28f;
        }
        if (prefilterAndFold) prefilterAndFold(n);
        synthesis(x, outSyn, oldBandE, start, effEnd, c2, cc, isTransient, lm, silence);

        for (int c = 0; c < cc; c++) {
            postfilterPeriod = Math.max(postfilterPeriod, COMBFILTER_MINPERIOD);
            postfilterPeriodOld = Math.max(postfilterPeriodOld, COMBFILTER_MINPERIOD);
            float[] mem = decodeMem[c];
            combFilter(mem, outSyn, mem, outSyn, postfilterPeriodOld, postfilterPeriod, mode.shortMdctSize,
                    postfilterGainOld, postfilterGain, postfilterTapsetOld, postfilterTapset, mode.window, overlap);
            if (lm != 0) {
                combFilter(mem, outSyn + mode.shortMdctSize, mem, outSyn + mode.shortMdctSize, postfilterPeriod,
                        postfilterPitch, n - mode.shortMdctSize, postfilterGain, postfilterGainNew, postfilterTapset,
                        postfilterTapsetNew, mode.window, overlap);
            }
        }
        postfilterPeriodOld = postfilterPeriod;
        postfilterGainOld = postfilterGain;
        postfilterTapsetOld = postfilterTapset;
        postfilterPeriod = postfilterPitch;
        postfilterGain = postfilterGainNew;
        postfilterTapset = postfilterTapsetNew;
        if (lm != 0) {
            postfilterPeriodOld = postfilterPeriod;
            postfilterGainOld = postfilterGain;
            postfilterTapsetOld = postfilterTapset;
        }

        if (c2 == 1) System.arraycopy(oldBandE, 0, oldBandE, nbEBands, nbEBands);

        if (!isTransient) {
            System.arraycopy(oldLogE, 0, oldLogE2, 0, 2 * nbEBands);
            System.arraycopy(oldBandE, 0, oldLogE, 0, 2 * nbEBands);
        } else {
            for (int i = 0; i < 2 * nbEBands; i++) oldLogE[i] = Math.min(oldLogE[i], oldBandE[i]);
        }
        float maxBackgroundIncrease = Math.min(160, lossDuration + mm) * 0.001f;
        for (int i = 0; i < 2 * nbEBands; i++) {
            backgroundLogE[i] = Math.min(backgroundLogE[i] + maxBackgroundIncrease, oldBandE[i]);
        }
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < start; i++) {
                oldBandE[c * nbEBands + i] = 0;
                oldLogE[c * nbEBands + i] = oldLogE2[c * nbEBands + i] = -28f;
            }
            for (int i = end; i < nbEBands; i++) {
                oldBandE[c * nbEBands + i] = 0;
                oldLogE[c * nbEBands + i] = oldLogE2[c * nbEBands + i] = -28f;
            }
        }
        rng = (int) dec.rng;

        deemphasis(pcm, pcmOffset, n, cc, outSyn);
        lossDuration = 0;
        prefilterAndFold = false;
        if (dec.tell() > 8 * len) return -1;
        if (dec.error) error = 1;
        return frameSize;
    }

    private void unquantCoarseEnergy(int start, int end, boolean intra, RangeDecoder dec, int c2, int lm) {
        int[] probModel = E_PROB_MODEL[lm * 2 + (intra ? 1 : 0)];
        float[] prev = {0, 0};
        float coef;
        float beta;
        if (intra) {
            coef = 0;
            beta = BETA_INTRA;
        } else {
            beta = BETA_COEF[lm];
            coef = PRED_COEF[lm];
        }
        int budget = dec.storage * 8;
        for (int i = start; i < end; i++) {
            for (int c = 0; c < c2; c++) {
                int qi;
                int tell = dec.tell();
                if (budget - tell >= 15) {
                    int pi = 2 * Math.min(i, 20);
                    qi = dec.laplace(probModel[pi] << 7, probModel[pi + 1] << 6);
                } else if (budget - tell >= 2) {
                    qi = dec.icdf(SMALL_ENERGY_ICDF, 2);
                    qi = (qi >> 1) ^ -(qi & 1);
                } else if (budget - tell >= 1) {
                    qi = dec.bitLogp(1) ? -1 : 0;
                } else {
                    qi = -1;
                }
                float q = qi;
                int at = i + c * mode.nbEBands;
                oldBandE[at] = Math.max(-9f, oldBandE[at]);
                float tmp = coef * oldBandE[at] + prev[c] + q;
                oldBandE[at] = tmp;
                prev[c] = prev[c] + q - beta * q;
            }
        }
    }

    private void unquantFineEnergy(int start, int end, int[] fineQuant, RangeDecoder dec, int c2) {
        for (int i = start; i < end; i++) {
            if (fineQuant[i] <= 0) continue;
            for (int c = 0; c < c2; c++) {
                int q2 = dec.bits(fineQuant[i]);
                float offset = (q2 + .5f) * (1 << (14 - fineQuant[i])) * (1.f / 16384) - .5f;
                oldBandE[i + c * mode.nbEBands] += offset;
            }
        }
    }

    private void unquantEnergyFinalise(int start, int end, int[] fineQuant, int[] finePriority, int bitsLeft,
                                       RangeDecoder dec, int c2) {
        for (int prio = 0; prio < 2; prio++) {
            for (int i = start; i < end && bitsLeft >= c2; i++) {
                if (fineQuant[i] >= Rate.MAX_FINE_BITS || finePriority[i] != prio) continue;
                for (int c = 0; c < c2; c++) {
                    int q2 = dec.bits(1);
                    float offset = (q2 - .5f) * (1 << (14 - fineQuant[i] - 1)) * (1.f / 16384);
                    oldBandE[i + c * mode.nbEBands] += offset;
                    bitsLeft--;
                }
            }
        }
    }

    private static void tfDecode(int start, int end, boolean isTransient, int[] tfRes, int lm, RangeDecoder dec) {
        long budget = dec.storage * 8L;
        long tell = dec.tell();
        int logp = isTransient ? 2 : 4;
        int tfSelectRsv = lm > 0 && tell + logp + 1 <= budget ? 1 : 0;
        budget -= tfSelectRsv;
        int tfChanged = 0;
        int curr = 0;
        for (int i = start; i < end; i++) {
            if (tell + logp <= budget) {
                curr ^= dec.bitLogp(logp) ? 1 : 0;
                tell = dec.tell();
                tfChanged |= curr;
            }
            tfRes[i] = curr;
            logp = isTransient ? 4 : 5;
        }
        int tfSelect = 0;
        int t = isTransient ? 4 : 0;
        if (tfSelectRsv != 0 && TF_SELECT_TABLE[lm][t + tfChanged] != TF_SELECT_TABLE[lm][t + 2 + tfChanged]) {
            tfSelect = dec.bitLogp(1) ? 1 : 0;
        }
        for (int i = start; i < end; i++) tfRes[i] = TF_SELECT_TABLE[lm][t + 2 * tfSelect + tfRes[i]];
    }

    private void antiCollapse(float[] x, int[] collapseMasks, int lm, int c2, int size, int start, int end,
                              int[] pulses, int seed) {
        int nb = mode.nbEBands;
        for (int i = start; i < end; i++) {
            int n0 = mode.eBands[i + 1] - mode.eBands[i];
            int depth = Integer.divideUnsigned(1 + pulses[i], n0) >> lm;
            float thresh = .5f * (float) Math.exp(0.6931471805599453094 * (-.125f * depth));
            float sqrt1 = (float) (1.0 / Math.sqrt(n0 << lm));
            for (int c = 0; c < c2; c++) {
                float prev1 = oldLogE[c * nb + i];
                float prev2 = oldLogE2[c * nb + i];
                if (c2 == 1) {
                    prev1 = Math.max(prev1, oldLogE[nb + i]);
                    prev2 = Math.max(prev2, oldLogE2[nb + i]);
                }
                float ediff = oldBandE[c * nb + i] - Math.min(prev1, prev2);
                ediff = Math.max(0, ediff);
                float r = 2.f * (float) Math.exp(0.6931471805599453094 * (-ediff));
                if (lm == 3) r *= 1.41421356f;
                r = Math.min(thresh, r);
                r = r * sqrt1;
                int xo = c * size + (mode.eBands[i] << lm);
                boolean renormalize = false;
                for (int k = 0; k < 1 << lm; k++) {
                    if ((collapseMasks[i * c2 + c] & 1 << k) == 0) {
                        for (int j = 0; j < n0; j++) {
                            seed = Bands.lcgRand(seed);
                            x[xo + (j << lm) + k] = (seed & 0x8000) != 0 ? r : -r;
                        }
                        renormalize = true;
                    }
                }
                if (renormalize) Bands.renormaliseVector(x, xo, n0 << lm, 1f);
            }
        }
    }

    private void denormaliseBands(float[] x, int xo, float[] freq, float[] bandLogE, int bandOffset, int start,
                                  int end, int mm, boolean silence) {
        int[] eBands = mode.eBands;
        int n = mm * mode.shortMdctSize;
        int bound = mm * eBands[end];
        if (silence) {
            bound = 0;
            start = end = 0;
        }
        int f = 0;
        int xi = xo + mm * eBands[start];
        for (int i = 0; i < mm * eBands[start]; i++) freq[f++] = 0;
        for (int i = start; i < end; i++) {
            int j = mm * eBands[i];
            int bandEnd = mm * eBands[i + 1];
            float lg = bandLogE[bandOffset + i] + E_MEANS[i];
            float g = (float) Math.exp(0.6931471805599453094 * Math.min(32.f, lg));
            do {
                freq[f++] = x[xi++] * g;
            } while (++j < bandEnd);
        }
        Arrays.fill(freq, bound, n, 0);
    }

    private void synthesis(float[] x, int outSyn, float[] bandE, int start, int effEnd, int c2, int cc,
                           boolean isTransient, int lm, boolean silence) {
        int overlap = mode.overlap;
        int nb = mode.nbEBands;
        int n = mode.shortMdctSize << lm;
        float[] freq = new float[n];
        int mm = 1 << lm;
        int b;
        int nbSize;
        int shift;
        if (isTransient) {
            b = mm;
            nbSize = mode.shortMdctSize;
            shift = mode.maxLM;
        } else {
            b = 1;
            nbSize = mode.shortMdctSize << lm;
            shift = mode.maxLM - lm;
        }

        if (cc == 2 && c2 == 1) {
            denormaliseBands(x, 0, freq, bandE, 0, start, effEnd, mm, silence);
            float[] freq2 = freq.clone();
            for (int k = 0; k < b; k++) mode.mdctBackward(freq2, k, decodeMem[0], outSyn + nbSize * k, shift, b);
            for (int k = 0; k < b; k++) mode.mdctBackward(freq, k, decodeMem[1], outSyn + nbSize * k, shift, b);
        } else if (cc == 1 && c2 == 2) {
            float[] freq2 = new float[n];
            denormaliseBands(x, 0, freq, bandE, 0, start, effEnd, mm, silence);
            denormaliseBands(x, n, freq2, bandE, nb, start, effEnd, mm, silence);
            for (int i = 0; i < n; i++) freq[i] = .5f * freq[i] + .5f * freq2[i];
            for (int k = 0; k < b; k++) mode.mdctBackward(freq, k, decodeMem[0], outSyn + nbSize * k, shift, b);
        } else {
            for (int c = 0; c < cc; c++) {
                denormaliseBands(x, c * n, freq, bandE, c * nb, start, effEnd, mm, silence);
                for (int k = 0; k < b; k++) mode.mdctBackward(freq, k, decodeMem[c], outSyn + nbSize * k, shift, b);
            }
        }
    }

    static void combFilter(float[] y, int yo, float[] x, int xo, int t0, int t1, int n, float g0, float g1,
                           int tapset0, int tapset1, float[] window, int overlap) {
        if (g0 == 0 && g1 == 0) {
            if (x != y || xo != yo) System.arraycopy(x, xo, y, yo, n);
            return;
        }
        t0 = Math.max(t0, COMBFILTER_MINPERIOD);
        t1 = Math.max(t1, COMBFILTER_MINPERIOD);
        float g00 = g0 * COMB_GAINS[tapset0][0];
        float g01 = g0 * COMB_GAINS[tapset0][1];
        float g02 = g0 * COMB_GAINS[tapset0][2];
        float g10 = g1 * COMB_GAINS[tapset1][0];
        float g11 = g1 * COMB_GAINS[tapset1][1];
        float g12 = g1 * COMB_GAINS[tapset1][2];
        float x1 = x[xo - t1 + 1];
        float x2 = x[xo - t1];
        float x3 = x[xo - t1 - 1];
        float x4 = x[xo - t1 - 2];
        if (g0 == g1 && t0 == t1 && tapset0 == tapset1) overlap = 0;
        int i;
        for (i = 0; i < overlap; i++) {
            float x0 = x[xo + i - t1 + 2];
            float f = window[i] * window[i];
            y[yo + i] = x[xo + i]
                    + ((1f - f) * g00) * x[xo + i - t0]
                    + ((1f - f) * g01) * (x[xo + i - t0 + 1] + x[xo + i - t0 - 1])
                    + ((1f - f) * g02) * (x[xo + i - t0 + 2] + x[xo + i - t0 - 2])
                    + (f * g10) * x2
                    + (f * g11) * (x1 + x3)
                    + (f * g12) * (x0 + x4);
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }
        if (g1 == 0) {
            if (x != y || xo != yo) System.arraycopy(x, xo + overlap, y, yo + overlap, n - overlap);
            return;
        }
        int yb = yo + i;
        int xb = xo + i;
        int count = n - i;
        x4 = x[xb - t1 - 2];
        x3 = x[xb - t1 - 1];
        x2 = x[xb - t1];
        x1 = x[xb - t1 + 1];
        for (int k = 0; k < count; k++) {
            float x0 = x[xb + k - t1 + 2];
            y[yb + k] = x[xb + k] + g10 * x2 + g11 * (x1 + x3) + g12 * (x0 + x4);
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }
    }

    private void deemphasis(float[] pcm, int pcmOffset, int n, int c2, int outSyn) {
        float coef0 = mode.preemph0;
        for (int c = 0; c < c2; c++) {
            float[] in = decodeMem[c];
            float m = preemphMemD[c];
            for (int j = 0; j < n; j++) {
                float tmp = in[outSyn + j] + VERY_SMALL + m;
                m = coef0 * tmp;
                pcm[pcmOffset + j * c2 + c] = tmp * (1 / 32768.f);
            }
            preemphMemD[c] = m;
        }
    }

    private void prefilterAndFold(int n) {
        int overlap = mode.overlap;
        float[] etmp = new float[overlap];
        for (int c = 0; c < channels; c++) {
            float[] mem = decodeMem[c];
            combFilter(etmp, 0, mem, DECODE_BUFFER_SIZE - n, postfilterPeriodOld, postfilterPeriod, overlap,
                    -postfilterGainOld, -postfilterGain, postfilterTapsetOld, postfilterTapset, null, 0);
            for (int i = 0; i < overlap / 2; i++) {
                mem[DECODE_BUFFER_SIZE - n + i] = mode.window[i] * etmp[overlap - 1 - i]
                        + mode.window[overlap - i - 1] * etmp[i];
            }
        }
    }

    private void decodeLost(int n, int lm) {
        int c2 = channels;
        int nbEBands = mode.nbEBands;
        int overlap = mode.overlap;
        int[] eBands = mode.eBands;
        int outSyn = DECODE_BUFFER_SIZE - n;
        boolean noiseBased = lossDuration >= 40 || start != 0 || skipPlc;
        if (noiseBased) {
            int effEnd = Math.max(start, Math.min(end, mode.effEBands));
            float[] x = new float[c2 * n];
            for (int c = 0; c < c2; c++) {
                System.arraycopy(decodeMem[c], n, decodeMem[c], 0, DECODE_BUFFER_SIZE - n + overlap);
            }
            if (prefilterAndFold) prefilterAndFold(n);
            float decay = lossDuration == 0 ? 1.5f : .5f;
            for (int c = 0; c < c2; c++) {
                for (int i = start; i < end; i++) {
                    int at = c * nbEBands + i;
                    oldBandE[at] = Math.max(backgroundLogE[at], oldBandE[at] - decay);
                }
            }
            int seed = rng;
            for (int c = 0; c < c2; c++) {
                for (int i = start; i < effEnd; i++) {
                    int boffs = n * c + (eBands[i] << lm);
                    int blen = (eBands[i + 1] - eBands[i]) << lm;
                    for (int j = 0; j < blen; j++) {
                        seed = Bands.lcgRand(seed);
                        x[boffs + j] = (float) (seed >> 20);
                    }
                    Bands.renormaliseVector(x, boffs, blen, 1f);
                }
            }
            rng = seed;
            synthesis(x, outSyn, oldBandE, start, effEnd, c2, c2, false, lm, false);
            prefilterAndFold = false;
            skipPlc = true;
        } else {
            float fade = 1f;
            int pitchIndex;
            if (lossDuration == 0) {
                lastPitchIndex = pitchIndex = plcPitchSearch(c2);
            } else {
                pitchIndex = lastPitchIndex;
                fade = .8f;
            }
            int excLength = Math.min(2 * pitchIndex, MAX_PERIOD);
            float[] excBuf = new float[MAX_PERIOD + LPC_ORDER];
            int exc = LPC_ORDER;
            float[] firTmp = new float[excLength];
            float[] window = mode.window;
            for (int c = 0; c < c2; c++) {
                float[] buf = decodeMem[c];
                for (int i = 0; i < MAX_PERIOD + LPC_ORDER; i++) {
                    excBuf[exc + i - LPC_ORDER] = buf[DECODE_BUFFER_SIZE - MAX_PERIOD - LPC_ORDER + i];
                }
                if (lossDuration == 0) {
                    float[] ac = new float[LPC_ORDER + 1];
                    autocorr(excBuf, exc, ac, window, overlap, LPC_ORDER, MAX_PERIOD);
                    ac[0] *= 1.0001f;
                    for (int i = 1; i <= LPC_ORDER; i++) ac[i] -= ac[i] * (0.008f * 0.008f) * i * i;
                    lpcFromAutocorr(lpc[c], ac, LPC_ORDER);
                }
                fir(excBuf, exc + MAX_PERIOD - excLength, lpc[c], firTmp, excLength, LPC_ORDER);
                System.arraycopy(firTmp, 0, excBuf, exc + MAX_PERIOD - excLength, excLength);

                float e1 = 1;
                float e2 = 1;
                int decayLength = excLength >> 1;
                for (int i = 0; i < decayLength; i++) {
                    float e = excBuf[exc + MAX_PERIOD - decayLength + i];
                    e1 += e * e;
                    e = excBuf[exc + MAX_PERIOD - 2 * decayLength + i];
                    e2 += e * e;
                }
                e1 = Math.min(e1, e2);
                float decay = (float) Math.sqrt(e1 / e2);

                System.arraycopy(buf, n, buf, 0, DECODE_BUFFER_SIZE - n);

                int extrapolationOffset = MAX_PERIOD - pitchIndex;
                int extrapolationLen = n + overlap;
                float attenuation = fade * decay;
                float s1 = 0;
                for (int i = 0, j = 0; i < extrapolationLen; i++, j++) {
                    if (j >= pitchIndex) {
                        j -= pitchIndex;
                        attenuation = attenuation * decay;
                    }
                    buf[DECODE_BUFFER_SIZE - n + i] = attenuation * excBuf[exc + extrapolationOffset + j];
                    float tmp = buf[DECODE_BUFFER_SIZE - MAX_PERIOD - n + extrapolationOffset + j];
                    s1 += tmp * tmp;
                }
                float[] lpcMem = new float[LPC_ORDER];
                for (int i = 0; i < LPC_ORDER; i++) lpcMem[i] = buf[DECODE_BUFFER_SIZE - n - 1 - i];
                iir(buf, DECODE_BUFFER_SIZE - n, lpc[c], extrapolationLen, LPC_ORDER, lpcMem);

                float s2 = 0;
                for (int i = 0; i < extrapolationLen; i++) {
                    float tmp = buf[DECODE_BUFFER_SIZE - n + i];
                    s2 += tmp * tmp;
                }
                if (!(s1 > 0.2f * s2)) {
                    for (int i = 0; i < extrapolationLen; i++) buf[DECODE_BUFFER_SIZE - n + i] = 0;
                } else if (s1 < s2) {
                    float ratio = (float) Math.sqrt((s1 / 2 + 1) / (s2 + 1));
                    for (int i = 0; i < overlap; i++) {
                        float g = 1f - window[i] * (1f - ratio);
                        buf[DECODE_BUFFER_SIZE - n + i] = g * buf[DECODE_BUFFER_SIZE - n + i];
                    }
                    for (int i = overlap; i < extrapolationLen; i++) {
                        buf[DECODE_BUFFER_SIZE - n + i] = ratio * buf[DECODE_BUFFER_SIZE - n + i];
                    }
                }
            }
            prefilterAndFold = true;
        }
        lossDuration = Math.min(10000, lossDuration + (1 << lm));
    }

    private int plcPitchSearch(int c2) {
        float[] lp = new float[DECODE_BUFFER_SIZE >> 1];
        pitchDownsample(c2, lp, DECODE_BUFFER_SIZE);
        int pitch = pitchSearch(lp, PLC_PITCH_LAG_MAX >> 1, lp, 0, DECODE_BUFFER_SIZE - PLC_PITCH_LAG_MAX,
                PLC_PITCH_LAG_MAX - PLC_PITCH_LAG_MIN);
        return PLC_PITCH_LAG_MAX - pitch;
    }

    private void pitchDownsample(int c2, float[] xlp, int len) {
        float[] x0 = decodeMem[0];
        for (int i = 1; i < len >> 1; i++) xlp[i] = .25f * x0[2 * i - 1] + .25f * x0[2 * i + 1] + .5f * x0[2 * i];
        xlp[0] = .25f * x0[1] + .5f * x0[0];
        if (c2 == 2) {
            float[] x1 = decodeMem[1];
            for (int i = 1; i < len >> 1; i++) xlp[i] += .25f * x1[2 * i - 1] + .25f * x1[2 * i + 1] + .5f * x1[2 * i];
            xlp[0] += .25f * x1[1] + .5f * x1[0];
        }
        float[] ac = new float[5];
        autocorr(xlp, 0, ac, null, 0, 4, len >> 1);
        ac[0] *= 1.0001f;
        for (int i = 1; i <= 4; i++) ac[i] -= ac[i] * (.008f * i) * (.008f * i);
        float[] lpc4 = new float[4];
        lpcFromAutocorr(lpc4, ac, 4);
        float tmp = 1f;
        for (int i = 0; i < 4; i++) {
            tmp = .9f * tmp;
            lpc4[i] = lpc4[i] * tmp;
        }
        float c1 = .8f;
        float[] lpc2 = {lpc4[0] + .8f, lpc4[1] + c1 * lpc4[0], lpc4[2] + c1 * lpc4[1], lpc4[3] + c1 * lpc4[2], c1 * lpc4[3]};
        float mem0 = 0, mem1 = 0, mem2 = 0, mem3 = 0, mem4 = 0;
        for (int i = 0; i < len >> 1; i++) {
            float sum = xlp[i] + lpc2[0] * mem0 + lpc2[1] * mem1 + lpc2[2] * mem2 + lpc2[3] * mem3 + lpc2[4] * mem4;
            mem4 = mem3;
            mem3 = mem2;
            mem2 = mem1;
            mem1 = mem0;
            mem0 = xlp[i];
            xlp[i] = sum;
        }
    }

    private static int pitchSearch(float[] xlp, int xo, float[] y, int yo, int len, int maxPitch) {
        int lag = len + maxPitch;
        float[] x4 = new float[len >> 2];
        float[] y4 = new float[lag >> 2];
        float[] xcorr = new float[maxPitch >> 1];
        for (int j = 0; j < len >> 2; j++) x4[j] = xlp[xo + 2 * j];
        for (int j = 0; j < lag >> 2; j++) y4[j] = y[yo + 2 * j];
        for (int i = 0; i < maxPitch >> 2; i++) {
            float sum = 0;
            for (int j = 0; j < len >> 2; j++) sum += x4[j] * y4[i + j];
            xcorr[i] = sum;
        }
        int[] best = {0, 1};
        findBestPitch(xcorr, y4, 0, len >> 2, maxPitch >> 2, best);
        for (int i = 0; i < maxPitch >> 1; i++) {
            xcorr[i] = 0;
            if (Math.abs(i - 2 * best[0]) > 2 && Math.abs(i - 2 * best[1]) > 2) continue;
            float sum = 0;
            for (int j = 0; j < len >> 1; j++) sum += xlp[xo + j] * y[yo + i + j];
            xcorr[i] = Math.max(-1, sum);
        }
        findBestPitch(xcorr, y, yo, len >> 1, maxPitch >> 1, best);
        int offset;
        if (best[0] > 0 && best[0] < (maxPitch >> 1) - 1) {
            float a = xcorr[best[0] - 1];
            float b = xcorr[best[0]];
            float c = xcorr[best[0] + 1];
            if ((c - a) > .7f * (b - a)) offset = 1;
            else if ((a - c) > .7f * (b - c)) offset = -1;
            else offset = 0;
        } else {
            offset = 0;
        }
        return 2 * best[0] - offset;
    }

    private static void findBestPitch(float[] xcorr, float[] y, int yo, int len, int maxPitch, int[] best) {
        float syy = 1;
        float[] bestNum = {-1, -1};
        float[] bestDen = {0, 0};
        best[0] = 0;
        best[1] = 1;
        for (int j = 0; j < len; j++) syy += y[yo + j] * y[yo + j];
        for (int i = 0; i < maxPitch; i++) {
            if (xcorr[i] > 0) {
                float x16 = xcorr[i] * 1e-12f;
                float num = x16 * x16;
                if (num * bestDen[1] > bestNum[1] * syy) {
                    if (num * bestDen[0] > bestNum[0] * syy) {
                        bestNum[1] = bestNum[0];
                        bestDen[1] = bestDen[0];
                        best[1] = best[0];
                        bestNum[0] = num;
                        bestDen[0] = syy;
                        best[0] = i;
                    } else {
                        bestNum[1] = num;
                        bestDen[1] = syy;
                        best[1] = i;
                    }
                }
            }
            syy += y[yo + i + len] * y[yo + i + len] - y[yo + i] * y[yo + i];
            syy = Math.max(1, syy);
        }
    }

    private static void autocorr(float[] x, int xo, float[] ac, float[] window, int overlap, int lag, int n) {
        float[] xx = new float[n];
        System.arraycopy(x, xo, xx, 0, n);
        if (overlap > 0) {
            for (int i = 0; i < overlap; i++) {
                xx[i] = x[xo + i] * window[i];
                xx[n - i - 1] = x[xo + n - i - 1] * window[i];
            }
        }
        for (int k = 0; k <= lag; k++) {
            float d = 0;
            for (int i = k; i < n; i++) d += xx[i] * xx[i - k];
            ac[k] = d;
        }
    }

    private static void lpcFromAutocorr(float[] lpc, float[] ac, int p) {
        float error = ac[0];
        Arrays.fill(lpc, 0, p, 0);
        if (ac[0] > 1e-10f) {
            for (int i = 0; i < p; i++) {
                float rr = 0;
                for (int j = 0; j < i; j++) rr += lpc[j] * ac[i - j];
                rr += ac[i + 1];
                float r = -(rr / error);
                lpc[i] = r;
                for (int j = 0; j < (i + 1) >> 1; j++) {
                    float tmp1 = lpc[j];
                    float tmp2 = lpc[i - 1 - j];
                    lpc[j] = tmp1 + r * tmp2;
                    lpc[i - 1 - j] = tmp2 + r * tmp1;
                }
                error = error - r * r * error;
                if (error <= .001f * ac[0]) break;
            }
        }
    }

    private static void fir(float[] x, int xo, float[] num, float[] y, int n, int ord) {
        for (int i = 0; i < n; i++) {
            float sum = x[xo + i];
            for (int j = 0; j < ord; j++) sum += num[ord - j - 1] * x[xo + i + j - ord];
            y[i] = sum;
        }
    }

    private static void iir(float[] x, int xo, float[] den, int n, int ord, float[] mem) {
        for (int i = 0; i < n; i++) {
            float sum = x[xo + i];
            for (int j = 0; j < ord; j++) sum -= den[j] * mem[j];
            for (int j = ord - 1; j >= 1; j--) mem[j] = mem[j - 1];
            mem[0] = sum;
            x[xo + i] = sum;
        }
    }
}
