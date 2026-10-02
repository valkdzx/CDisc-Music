package dev.valkdz.cdisc.audio.media.codec.opus;

final class Silk {

    static final int MAX_LPC_ORDER = 16;
    static final int MIN_LPC_ORDER = 10;
    static final int LTP_ORDER = 5;
    static final int MAX_NB_SUBFR = 4;
    static final int MAX_FRAME_LENGTH = 320;
    static final int MAX_SUB_FRAME_LENGTH = 80;
    static final int SHELL_CODEC_FRAME_LENGTH = 16;
    static final int LOG2_SHELL_CODEC_FRAME_LENGTH = 4;
    static final int SILK_MAX_PULSES = 16;
    static final int N_RATE_LEVELS = 10;
    static final int MAX_NB_SHELL_BLOCKS = MAX_FRAME_LENGTH / SHELL_CODEC_FRAME_LENGTH;
    static final int TYPE_NO_VOICE_ACTIVITY = 0;
    static final int TYPE_UNVOICED = 1;
    static final int TYPE_VOICED = 2;
    static final int CODE_INDEPENDENTLY = 0;
    static final int CODE_INDEPENDENTLY_NO_LTP_SCALING = 1;
    static final int CODE_CONDITIONALLY = 2;
    static final int NLSF_QUANT_MAX_AMPLITUDE = 4;
    static final int N_LEVELS_QGAIN = 64;
    static final int MIN_DELTA_GAIN_QUANT = -4;
    static final int MAX_DELTA_GAIN_QUANT = 36;
    static final int QUANT_LEVEL_ADJUST_Q10 = 80;
    static final int BWE_AFTER_LOSS_Q16 = 63570;
    static final int STEREO_INTERP_LEN_MS = 8;
    static final int STEREO_QUANT_SUB_STEPS = 5;
    private static final int MAX_LPC_STABILIZE_ITERATIONS = 16;
    private static final int MIN_INV_GAIN_Q30 = fixConst(1.0 / 1e4f, 30);
    private static final int GAIN_OFFSET = (2 * 128) / 6 + 16 * 128;
    private static final int GAIN_INV_SCALE_Q16 = (65536 * (((88 - 2) * 128) / 6)) / (N_LEVELS_QGAIN - 1);
    static final int[][] QUANTIZATION_OFFSETS_Q10 = {{100, 240}, {32, 100}};

    private Silk() {
    }

    static int fixConst(double c, int q) {
        return (int) (c * (1L << q) + 0.5);
    }

    static int smulwb(int a, int b) {
        return (int) (((long) a * (short) b) >> 16);
    }

    static int smlawb(int a, int b, int c) {
        return a + (int) (((long) b * (short) c) >> 16);
    }

    static int smulww(int a, int b) {
        return (int) (((long) a * b) >> 16);
    }

    static int smlaww(int a, int b, int c) {
        return a + (int) (((long) b * c) >> 16);
    }

    static int smulbb(int a, int b) {
        return (short) a * (short) b;
    }

    static int smlabb(int a, int b, int c) {
        return a + (short) b * (short) c;
    }

    static int smmul(int a, int b) {
        return (int) (((long) a * b) >> 32);
    }

    static int rshiftRound(int a, int shift) {
        return shift == 1 ? (a >> 1) + (a & 1) : ((a >> (shift - 1)) + 1) >> 1;
    }

    static long rshiftRound64(long a, int shift) {
        return shift == 1 ? (a >> 1) + (a & 1) : ((a >> (shift - 1)) + 1) >> 1;
    }

    static int sat16(int a) {
        return a > Short.MAX_VALUE ? Short.MAX_VALUE : Math.max(a, Short.MIN_VALUE);
    }

    static int addSat32(int a, int b) {
        long sum = (long) a + b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : sum < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) sum;
    }

    static int subSat32(int a, int b) {
        long diff = (long) a - b;
        return diff > Integer.MAX_VALUE ? Integer.MAX_VALUE : diff < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) diff;
    }

    static int limit(int a, int l1, int l2) {
        return l1 > l2 ? (a > l1 ? l1 : Math.max(a, l2)) : (a > l2 ? l2 : Math.max(a, l1));
    }

    static int lshiftSat32(int a, int shift) {
        return limit(a, Integer.MIN_VALUE >> shift, Integer.MAX_VALUE >> shift) << shift;
    }

    static int clz32(int in) {
        return in == 0 ? 32 : Integer.numberOfLeadingZeros(in);
    }

    static int div32varQ(int a32, int b32, int qres) {
        int aHeadrm = clz32(Math.abs(a32)) - 1;
        int aNrm = a32 << aHeadrm;
        int bHeadrm = clz32(Math.abs(b32)) - 1;
        int bNrm = b32 << bHeadrm;
        int bInv = (Integer.MAX_VALUE >> 2) / (bNrm >> 16);
        int result = smulwb(aNrm, bInv);
        aNrm = aNrm - (smmul(bNrm, result) << 3);
        result = smlawb(result, aNrm, bInv);
        int lshift = 29 + aHeadrm - bHeadrm - qres;
        if (lshift < 0) return lshiftSat32(result, -lshift);
        return lshift < 32 ? result >> lshift : 0;
    }

    static int inverse32varQ(int b32, int qres) {
        int bHeadrm = clz32(Math.abs(b32)) - 1;
        int bNrm = b32 << bHeadrm;
        int bInv = (Integer.MAX_VALUE >> 2) / (bNrm >> 16);
        int result = bInv << 16;
        int errQ32 = ((1 << 29) - smulwb(bNrm, bInv)) << 3;
        result = smlaww(result, errQ32, bInv);
        int lshift = 61 - bHeadrm - qres;
        if (lshift <= 0) return lshiftSat32(result, -lshift);
        return lshift < 32 ? result >> lshift : 0;
    }

    static int log2lin(int inLogQ7) {
        if (inLogQ7 < 0) return 0;
        if (inLogQ7 >= 3967) return Integer.MAX_VALUE;
        int out = 1 << (inLogQ7 >> 7);
        int fracQ7 = inLogQ7 & 0x7F;
        if (inLogQ7 < 2048) {
            out = out + ((out * smlawb(fracQ7, smulbb(fracQ7, 128 - fracQ7), -174)) >> 7);
        } else {
            out = out + (out >> 7) * smlawb(fracQ7, smulbb(fracQ7, 128 - fracQ7), -174);
        }
        return out;
    }

    // Returns the updated previous gain index.
    static int gainsDequant(int[] gainQ16, int[] ind, int prevInd, boolean conditional, int nbSubfr) {
        for (int k = 0; k < nbSubfr; k++) {
            if (k == 0 && !conditional) {
                prevInd = Math.max(ind[k], prevInd - 16);
            } else {
                int indTmp = ind[k] + MIN_DELTA_GAIN_QUANT;
                int threshold = 2 * MAX_DELTA_GAIN_QUANT - N_LEVELS_QGAIN + prevInd;
                if (indTmp > threshold) prevInd += (indTmp << 1) - threshold;
                else prevInd += indTmp;
            }
            prevInd = limit(prevInd, 0, N_LEVELS_QGAIN - 1);
            gainQ16[k] = log2lin(Math.min(smulwb(GAIN_INV_SCALE_Q16, prevInd) + GAIN_OFFSET, 3967));
        }
        return prevInd;
    }

    static final class NlsfCodebook {
        final int nVectors;
        final int order;
        final int quantStepSizeQ16;
        final int[] cb1NlsfQ8;
        final int[] cb1WghtQ9;
        final int[] cb1Icdf;
        final int[] predQ8;
        final int[] ecSel;
        final int[] ecIcdf;
        final int[] deltaMinQ15;

        NlsfCodebook(int nVectors, int order, double step, int[] cb1, int[] wght, int[] cb1Icdf, int[] pred,
                     int[] ecSel, int[] ecIcdf, int[] deltaMin) {
            this.nVectors = nVectors;
            this.order = order;
            this.quantStepSizeQ16 = fixConst(step, 16);
            this.cb1NlsfQ8 = cb1;
            this.cb1WghtQ9 = wght;
            this.cb1Icdf = cb1Icdf;
            this.predQ8 = pred;
            this.ecSel = ecSel;
            this.ecIcdf = ecIcdf;
            this.deltaMinQ15 = deltaMin;
        }
    }

    static final NlsfCodebook NLSF_CB_NB_MB = new NlsfCodebook(32, 10, 0.18, SilkTables.NLSF_CB1_NB_MB_Q8,
            SilkTables.NLSF_CB1_WGHT_Q9, SilkTables.NLSF_CB1_I_CDF_NB_MB, SilkTables.NLSF_PRED_NB_MB_Q8,
            SilkTables.NLSF_CB2_SELECT_NB_MB, SilkTables.NLSF_CB2_I_CDF_NB_MB, SilkTables.NLSF_DELTA_MIN_NB_MB_Q15);
    static final NlsfCodebook NLSF_CB_WB = new NlsfCodebook(32, 16, 0.15, SilkTables.NLSF_CB1_WB_Q8,
            SilkTables.NLSF_CB1_WB_WGHT_Q9, SilkTables.NLSF_CB1_I_CDF_WB, SilkTables.NLSF_PRED_WB_Q8,
            SilkTables.NLSF_CB2_SELECT_WB, SilkTables.NLSF_CB2_I_CDF_WB, SilkTables.NLSF_DELTA_MIN_WB_Q15);

    static void nlsfUnpack(int[] ecIx, int[] predQ8, NlsfCodebook cb, int cb1Index) {
        int sel = cb1Index * cb.order / 2;
        for (int i = 0; i < cb.order; i += 2) {
            int entry = cb.ecSel[sel++];
            ecIx[i] = smulbb((entry >> 1) & 7, 2 * NLSF_QUANT_MAX_AMPLITUDE + 1);
            predQ8[i] = cb.predQ8[i + (entry & 1) * (cb.order - 1)];
            ecIx[i + 1] = smulbb((entry >> 5) & 7, 2 * NLSF_QUANT_MAX_AMPLITUDE + 1);
            predQ8[i + 1] = cb.predQ8[i + ((entry >> 4) & 1) * (cb.order - 1) + 1];
        }
    }

    static void nlsfDecode(int[] nlsfQ15, int[] indices, NlsfCodebook cb) {
        int[] predQ8 = new int[MAX_LPC_ORDER];
        int[] ecIx = new int[MAX_LPC_ORDER];
        int[] resQ10 = new int[MAX_LPC_ORDER];
        nlsfUnpack(ecIx, predQ8, cb, indices[0]);

        int outQ10 = 0;
        int adj = fixConst(0.1, 10);
        for (int i = cb.order - 1; i >= 0; i--) {
            int predQ10 = smulbb(outQ10, predQ8[i]) >> 8;
            outQ10 = indices[i + 1] << 10;
            if (outQ10 > 0) outQ10 = (short) (outQ10 - adj);
            else if (outQ10 < 0) outQ10 = (short) (outQ10 + adj);
            outQ10 = smlawb(predQ10, outQ10, cb.quantStepSizeQ16);
            resQ10[i] = (short) outQ10;
        }

        int base = indices[0] * cb.order;
        for (int i = 0; i < cb.order; i++) {
            int tmp = ((resQ10[i] << 14) / cb.cb1WghtQ9[base + i]) + (((short) cb.cb1NlsfQ8[base + i]) << 7);
            nlsfQ15[i] = limit(tmp, 0, 32767);
        }
        nlsfStabilize(nlsfQ15, cb.deltaMinQ15, cb.order);
    }

    static void nlsfStabilize(int[] nlsf, int[] deltaMin, int l) {
        int loops;
        for (loops = 0; loops < 20; loops++) {
            int minDiff = nlsf[0] - deltaMin[0];
            int idx = 0;
            for (int i = 1; i <= l - 1; i++) {
                int diff = nlsf[i] - (nlsf[i - 1] + deltaMin[i]);
                if (diff < minDiff) {
                    minDiff = diff;
                    idx = i;
                }
            }
            int diff = (1 << 15) - (nlsf[l - 1] + deltaMin[l]);
            if (diff < minDiff) {
                minDiff = diff;
                idx = l;
            }
            if (minDiff >= 0) return;
            if (idx == 0) {
                nlsf[0] = deltaMin[0];
            } else if (idx == l) {
                nlsf[l - 1] = (1 << 15) - deltaMin[l];
            } else {
                int minCenter = 0;
                for (int k = 0; k < idx; k++) minCenter += deltaMin[k];
                minCenter += deltaMin[idx] >> 1;
                int maxCenter = 1 << 15;
                for (int k = l; k > idx; k--) maxCenter -= deltaMin[k];
                maxCenter -= deltaMin[idx] >> 1;
                int center = (short) limit(rshiftRound(nlsf[idx - 1] + nlsf[idx], 1), minCenter, maxCenter);
                nlsf[idx - 1] = (short) (center - (deltaMin[idx] >> 1));
                nlsf[idx] = (short) (nlsf[idx - 1] + deltaMin[idx]);
            }
        }
        for (int i = 1; i < l; i++) {
            int value = nlsf[i];
            int j;
            for (j = i - 1; j >= 0 && value < nlsf[j]; j--) nlsf[j + 1] = nlsf[j];
            nlsf[j + 1] = value;
        }
        nlsf[0] = Math.max(nlsf[0], deltaMin[0]);
        for (int i = 1; i < l; i++) nlsf[i] = Math.max(nlsf[i], (short) sat16(nlsf[i - 1] + deltaMin[i]));
        nlsf[l - 1] = Math.min(nlsf[l - 1], (1 << 15) - deltaMin[l]);
        for (int i = l - 2; i >= 0; i--) nlsf[i] = Math.min(nlsf[i], nlsf[i + 1] - deltaMin[i + 1]);
    }

    private static final int[] ORDERING16 = {0, 15, 8, 7, 4, 11, 12, 3, 2, 13, 10, 5, 6, 9, 14, 1};
    private static final int[] ORDERING10 = {0, 9, 6, 3, 4, 5, 8, 1, 2, 7};
    private static final int QA = 16;

    private static void findPoly(int[] out, int[] cLsf, int off, int dd) {
        out[0] = 1 << QA;
        out[1] = -cLsf[off];
        for (int k = 1; k < dd; k++) {
            int ftmp = cLsf[off + 2 * k];
            out[k + 1] = (out[k - 1] << 1) - (int) rshiftRound64((long) ftmp * out[k], QA);
            for (int n = k; n > 1; n--) out[n] += out[n - 2] - (int) rshiftRound64((long) ftmp * out[n - 1], QA);
            out[1] -= ftmp;
        }
    }

    static void nlsf2a(int[] aQ12, int[] nlsf, int d) {
        int[] ordering = d == 16 ? ORDERING16 : ORDERING10;
        int[] cosLsf = new int[MAX_LPC_ORDER];
        int[] p = new int[MAX_LPC_ORDER / 2 + 1];
        int[] q = new int[MAX_LPC_ORDER / 2 + 1];
        int[] a32 = new int[MAX_LPC_ORDER];
        for (int k = 0; k < d; k++) {
            int fInt = nlsf[k] >> (15 - 7);
            int fFrac = nlsf[k] - (fInt << (15 - 7));
            int cosVal = SilkTables.LSFCOS_TAB_FIX_Q12[fInt];
            int delta = SilkTables.LSFCOS_TAB_FIX_Q12[fInt + 1] - cosVal;
            cosLsf[ordering[k]] = rshiftRound((cosVal << 8) + delta * fFrac, 20 - QA);
        }
        int dd = d >> 1;
        findPoly(p, cosLsf, 0, dd);
        findPoly(q, cosLsf, 1, dd);
        for (int k = 0; k < dd; k++) {
            int ptmp = p[k + 1] + p[k];
            int qtmp = q[k + 1] - q[k];
            a32[k] = -qtmp - ptmp;
            a32[d - k - 1] = qtmp - ptmp;
        }
        lpcFit(aQ12, a32, 12, QA + 1, d);
        for (int i = 0; lpcInversePredGain(aQ12, d) == 0 && i < MAX_LPC_STABILIZE_ITERATIONS; i++) {
            bwexpander32(a32, d, 65536 - (2 << i));
            for (int k = 0; k < d; k++) aQ12[k] = (short) rshiftRound(a32[k], QA + 1 - 12);
        }
    }

    static void lpcFit(int[] aOut, int[] aIn, int qout, int qin, int d) {
        int i;
        int idx = 0;
        for (i = 0; i < 10; i++) {
            int maxabs = 0;
            for (int k = 0; k < d; k++) {
                int absval = Math.abs(aIn[k]);
                if (absval > maxabs) {
                    maxabs = absval;
                    idx = k;
                }
            }
            maxabs = rshiftRound(maxabs, qin - qout);
            if (maxabs > Short.MAX_VALUE) {
                maxabs = Math.min(maxabs, 163838);
                int chirp = fixConst(0.999, 16) - ((maxabs - Short.MAX_VALUE) << 14) / ((maxabs * (idx + 1)) >> 2);
                bwexpander32(aIn, d, chirp);
            } else {
                break;
            }
        }
        if (i == 10) {
            for (int k = 0; k < d; k++) {
                aOut[k] = (short) sat16(rshiftRound(aIn[k], qin - qout));
                aIn[k] = aOut[k] << (qin - qout);
            }
        } else {
            for (int k = 0; k < d; k++) aOut[k] = (short) rshiftRound(aIn[k], qin - qout);
        }
    }

    static void bwexpander(int[] ar, int d, int chirpQ16) {
        int chirpMinusOne = chirpQ16 - 65536;
        for (int i = 0; i < d - 1; i++) {
            ar[i] = (short) rshiftRound(chirpQ16 * ar[i], 16);
            chirpQ16 += rshiftRound(chirpQ16 * chirpMinusOne, 16);
        }
        ar[d - 1] = (short) rshiftRound(chirpQ16 * ar[d - 1], 16);
    }

    static void bwexpander32(int[] ar, int d, int chirpQ16) {
        int chirpMinusOne = chirpQ16 - 65536;
        for (int i = 0; i < d - 1; i++) {
            ar[i] = smulww(chirpQ16, ar[i]);
            chirpQ16 += rshiftRound(chirpQ16 * chirpMinusOne, 16);
        }
        ar[d - 1] = smulww(chirpQ16, ar[d - 1]);
    }

    private static final int A_LIMIT = fixConst(0.99975, 24);

    static int lpcInversePredGain(int[] aQ12, int order) {
        int[] a = new int[MAX_LPC_ORDER];
        int dcResp = 0;
        for (int k = 0; k < order; k++) {
            dcResp += aQ12[k];
            a[k] = aQ12[k] << (24 - 12);
        }
        if (dcResp >= 4096) return 0;
        int invGain = 1 << 30;
        int k;
        for (k = order - 1; k > 0; k--) {
            if (a[k] > A_LIMIT || a[k] < -A_LIMIT) return 0;
            int rc = -(a[k] << (31 - 24));
            int rcMult1 = (1 << 30) - smmul(rc, rc);
            invGain = smmul(invGain, rcMult1) << 2;
            if (invGain < MIN_INV_GAIN_Q30) return 0;
            int mult2Q = 32 - clz32(Math.abs(rcMult1));
            int rcMult2 = inverse32varQ(rcMult1, mult2Q + 30);
            for (int n = 0; n < (k + 1) >> 1; n++) {
                int tmp1 = a[n];
                int tmp2 = a[k - n - 1];
                long tmp64 = rshiftRound64((long) subSat32(tmp1, (int) rshiftRound64((long) tmp2 * rc, 31)) * rcMult2, mult2Q);
                if (tmp64 > Integer.MAX_VALUE || tmp64 < Integer.MIN_VALUE) return 0;
                a[n] = (int) tmp64;
                tmp64 = rshiftRound64((long) subSat32(tmp2, (int) rshiftRound64((long) tmp1 * rc, 31)) * rcMult2, mult2Q);
                if (tmp64 > Integer.MAX_VALUE || tmp64 < Integer.MIN_VALUE) return 0;
                a[k - n - 1] = (int) tmp64;
            }
        }
        if (a[k] > A_LIMIT || a[k] < -A_LIMIT) return 0;
        int rc = -(a[0] << (31 - 24));
        int rcMult1 = (1 << 30) - smmul(rc, rc);
        invGain = smmul(invGain, rcMult1) << 2;
        if (invGain < MIN_INV_GAIN_Q30) return 0;
        return invGain;
    }

    static void lpcAnalysisFilter(int[] out, int outOff, int[] in, int inOff, int[] b, int len, int d) {
        for (int ix = d; ix < len; ix++) {
            int p = inOff + ix - 1;
            int acc = smulbb(in[p], b[0]);
            for (int j = 1; j < d; j++) acc += (short) in[p - j] * (short) b[j];
            acc = (in[p + 1] << 12) - acc;
            out[outOff + ix] = (short) sat16(rshiftRound(acc, 12));
        }
        for (int j = 0; j < d; j++) out[outOff + j] = 0;
    }

    static void decodePitch(int lagIndex, int contourIndex, int[] pitchLags, int fsKHz, int nbSubfr) {
        int[] cb;
        int cbkSize;
        if (fsKHz == 8) {
            if (nbSubfr == 4) {
                cb = SilkTables.CB_LAGS_STAGE2;
                cbkSize = 11;
            } else {
                cb = SilkTables.CB_LAGS_STAGE2_10_MS;
                cbkSize = 3;
            }
        } else {
            if (nbSubfr == 4) {
                cb = SilkTables.CB_LAGS_STAGE3;
                cbkSize = 34;
            } else {
                cb = SilkTables.CB_LAGS_STAGE3_10_MS;
                cbkSize = 12;
            }
        }
        int minLag = smulbb(2, fsKHz);
        int maxLag = smulbb(18, fsKHz);
        int lag = minLag + lagIndex;
        for (int k = 0; k < nbSubfr; k++) {
            pitchLags[k] = limit(lag + cb[k * cbkSize + contourIndex], minLag, maxLag);
        }
    }

    static void stereoDecodePred(RangeDecoder dec, int[] predQ13) {
        int[][] ix = new int[2][3];
        int n = dec.icdf(SilkTables.STEREO_PRED_JOINT_I_CDF, 8);
        ix[0][2] = n / 5;
        ix[1][2] = n - 5 * ix[0][2];
        for (n = 0; n < 2; n++) {
            ix[n][0] = dec.icdf(SilkTables.UNIFORM3_I_CDF, 8);
            ix[n][1] = dec.icdf(SilkTables.UNIFORM5_I_CDF, 8);
        }
        for (n = 0; n < 2; n++) {
            ix[n][0] += 3 * ix[n][2];
            int low = SilkTables.STEREO_PRED_QUANT_Q13[ix[n][0]];
            int step = smulwb(SilkTables.STEREO_PRED_QUANT_Q13[ix[n][0] + 1] - low, fixConst(0.5 / STEREO_QUANT_SUB_STEPS, 16));
            predQ13[n] = smlabb(low, step, 2 * ix[n][1] + 1);
        }
        predQ13[0] -= predQ13[1];
    }

    static void shellDecoder(int[] pulses0, int off, RangeDecoder dec, int pulses4) {
        int[] p3 = new int[2];
        int[] p2 = new int[4];
        int[] p1 = new int[8];
        split(p3, 0, dec, pulses4, SilkTables.SHELL_CODE_TABLE3);
        split(p2, 0, dec, p3[0], SilkTables.SHELL_CODE_TABLE2);
        split(p1, 0, dec, p2[0], SilkTables.SHELL_CODE_TABLE1);
        split(pulses0, off, dec, p1[0], SilkTables.SHELL_CODE_TABLE0);
        split(pulses0, off + 2, dec, p1[1], SilkTables.SHELL_CODE_TABLE0);
        split(p1, 2, dec, p2[1], SilkTables.SHELL_CODE_TABLE1);
        split(pulses0, off + 4, dec, p1[2], SilkTables.SHELL_CODE_TABLE0);
        split(pulses0, off + 6, dec, p1[3], SilkTables.SHELL_CODE_TABLE0);
        split(p2, 2, dec, p3[1], SilkTables.SHELL_CODE_TABLE2);
        split(p1, 4, dec, p2[2], SilkTables.SHELL_CODE_TABLE1);
        split(pulses0, off + 8, dec, p1[4], SilkTables.SHELL_CODE_TABLE0);
        split(pulses0, off + 10, dec, p1[5], SilkTables.SHELL_CODE_TABLE0);
        split(p1, 6, dec, p2[3], SilkTables.SHELL_CODE_TABLE1);
        split(pulses0, off + 12, dec, p1[6], SilkTables.SHELL_CODE_TABLE0);
        split(pulses0, off + 14, dec, p1[7], SilkTables.SHELL_CODE_TABLE0);
    }

    private static void split(int[] child, int at, RangeDecoder dec, int p, int[] table) {
        if (p > 0) {
            child[at] = dec.icdf(table, SilkTables.SHELL_CODE_TABLE_OFFSETS[p], 8);
            child[at + 1] = p - child[at];
        } else {
            child[at] = 0;
            child[at + 1] = 0;
        }
    }

    static void decodePulses(RangeDecoder dec, int[] pulses, int signalType, int quantOffsetType, int frameLength) {
        int[] sumPulses = new int[MAX_NB_SHELL_BLOCKS];
        int[] nLshifts = new int[MAX_NB_SHELL_BLOCKS];
        int rateLevel = dec.icdf(SilkTables.RATE_LEVELS_I_CDF, (signalType >> 1) * 9, 8);
        int iter = frameLength >> LOG2_SHELL_CODEC_FRAME_LENGTH;
        if (iter * SHELL_CODEC_FRAME_LENGTH < frameLength) iter++;

        int cdf = rateLevel * 18;
        for (int i = 0; i < iter; i++) {
            nLshifts[i] = 0;
            sumPulses[i] = dec.icdf(SilkTables.PULSES_PER_BLOCK_I_CDF, cdf, 8);
            while (sumPulses[i] == SILK_MAX_PULSES + 1) {
                nLshifts[i]++;
                sumPulses[i] = dec.icdf(SilkTables.PULSES_PER_BLOCK_I_CDF,
                        (N_RATE_LEVELS - 1) * 18 + (nLshifts[i] == 10 ? 1 : 0), 8);
            }
        }
        for (int i = 0; i < iter; i++) {
            int off = i * SHELL_CODEC_FRAME_LENGTH;
            if (sumPulses[i] > 0) {
                shellDecoder(pulses, off, dec, sumPulses[i]);
            } else {
                for (int k = 0; k < SHELL_CODEC_FRAME_LENGTH; k++) pulses[off + k] = 0;
            }
        }
        for (int i = 0; i < iter; i++) {
            if (nLshifts[i] > 0) {
                int nls = nLshifts[i];
                int off = i * SHELL_CODEC_FRAME_LENGTH;
                for (int k = 0; k < SHELL_CODEC_FRAME_LENGTH; k++) {
                    int absQ = pulses[off + k];
                    for (int j = 0; j < nls; j++) {
                        absQ <<= 1;
                        absQ += dec.icdf(SilkTables.LSB_I_CDF, 8);
                    }
                    pulses[off + k] = absQ;
                }
                sumPulses[i] |= nls << 5;
            }
        }

        int[] icdf = new int[2];
        int signOff = smulbb(7, quantOffsetType + (signalType << 1));
        int length = (frameLength + SHELL_CODEC_FRAME_LENGTH / 2) >> LOG2_SHELL_CODEC_FRAME_LENGTH;
        int q = 0;
        for (int i = 0; i < length; i++) {
            int p = sumPulses[i];
            if (p > 0) {
                icdf[0] = SilkTables.SIGN_I_CDF[signOff + Math.min(p & 0x1F, 6)];
                for (int j = 0; j < SHELL_CODEC_FRAME_LENGTH; j++) {
                    if (pulses[q + j] > 0) pulses[q + j] *= (dec.icdf(icdf, 8) << 1) - 1;
                }
            }
            q += SHELL_CODEC_FRAME_LENGTH;
        }
    }
}
