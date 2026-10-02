package dev.valkdz.cdisc.audio.media.codec.opus;

import java.util.Arrays;

import static dev.valkdz.cdisc.audio.media.codec.opus.Silk.*;

final class SilkChannel {

    private static final int[][] LTP_VQ = {SilkTables.LTP_GAIN_VQ_0, SilkTables.LTP_GAIN_VQ_1, SilkTables.LTP_GAIN_VQ_2};
    private static final int[][] LTP_GAIN_ICDF = {SilkTables.LTP_GAIN_I_CDF_0, SilkTables.LTP_GAIN_I_CDF_1, SilkTables.LTP_GAIN_I_CDF_2};

    int prevGainQ16;
    final int[] excQ14 = new int[MAX_FRAME_LENGTH];
    final int[] sLpcQ14Buf = new int[MAX_LPC_ORDER];
    final int[] outBuf = new int[MAX_FRAME_LENGTH + 2 * MAX_SUB_FRAME_LENGTH];
    int lagPrev;
    int lastGainIndex;
    int fsKHz;
    int fsApiHz;
    int nbSubfr;
    int frameLength;
    int subfrLength;
    int ltpMemLength;
    int lpcOrder;
    final int[] prevNlsfQ15 = new int[MAX_LPC_ORDER];
    boolean firstFrameAfterReset;
    int[] pitchLagLowBitsIcdf;
    int[] pitchContourIcdf;
    int nFramesDecoded;
    int nFramesPerPacket;
    int ecPrevSignalType;
    int ecPrevLagIndex;
    final int[] vadFlags = new int[3];
    int lbrrFlag;
    final int[] lbrrFlags = new int[3];
    final Resampler resampler = new Resampler();
    NlsfCodebook nlsfCb;
    int lossCnt;
    int prevSignalType;

    final int[] gainsIndices = new int[MAX_NB_SUBFR];
    final int[] ltpIndex = new int[MAX_NB_SUBFR];
    final int[] nlsfIndices = new int[MAX_LPC_ORDER + 1];
    int lagIndex;
    int contourIndex;
    int signalType;
    int quantOffsetType;
    int nlsfInterpCoefQ2;
    int perIndex;
    int ltpScaleIndex;
    int seed;

    private final int[] pitchL = new int[MAX_NB_SUBFR];
    private final int[] gainsQ16 = new int[MAX_NB_SUBFR];
    private final int[][] predCoefQ12 = new int[2][MAX_LPC_ORDER];
    private final int[] ltpCoefQ14 = new int[LTP_ORDER * MAX_NB_SUBFR];
    private int ltpScaleQ14;

    void reset() {
        prevGainQ16 = 65536;
        Arrays.fill(excQ14, 0);
        Arrays.fill(sLpcQ14Buf, 0);
        Arrays.fill(outBuf, 0);
        lagPrev = 0;
        lastGainIndex = 0;
        fsKHz = 0;
        fsApiHz = 0;
        nbSubfr = 0;
        frameLength = 0;
        subfrLength = 0;
        ltpMemLength = 0;
        lpcOrder = 0;
        Arrays.fill(prevNlsfQ15, 0);
        firstFrameAfterReset = true;
        nFramesDecoded = 0;
        nFramesPerPacket = 0;
        ecPrevSignalType = 0;
        ecPrevLagIndex = 0;
        Arrays.fill(vadFlags, 0);
        lbrrFlag = 0;
        Arrays.fill(lbrrFlags, 0);
        resampler.clear();
        lossCnt = 0;
        prevSignalType = 0;
    }

    void setFs(int fs, int fsApi) {
        subfrLength = smulbb(5, fs);
        int length = smulbb(nbSubfr, subfrLength);
        if (fsKHz != fs || fsApiHz != fsApi) {
            resampler.init(smulbb(fs, 1000), fsApi);
            fsApiHz = fsApi;
        }
        if (fsKHz != fs || length != frameLength) {
            if (fs == 8) {
                pitchContourIcdf = nbSubfr == MAX_NB_SUBFR ? SilkTables.PITCH_CONTOUR_NB_I_CDF : SilkTables.PITCH_CONTOUR_10_MS_NB_I_CDF;
            } else {
                pitchContourIcdf = nbSubfr == MAX_NB_SUBFR ? SilkTables.PITCH_CONTOUR_I_CDF : SilkTables.PITCH_CONTOUR_10_MS_I_CDF;
            }
            if (fsKHz != fs) {
                ltpMemLength = smulbb(20, fs);
                if (fs == 8 || fs == 12) {
                    lpcOrder = MIN_LPC_ORDER;
                    nlsfCb = NLSF_CB_NB_MB;
                } else {
                    lpcOrder = MAX_LPC_ORDER;
                    nlsfCb = NLSF_CB_WB;
                }
                pitchLagLowBitsIcdf = fs == 16 ? SilkTables.UNIFORM8_I_CDF : fs == 12 ? SilkTables.UNIFORM6_I_CDF : SilkTables.UNIFORM4_I_CDF;
                firstFrameAfterReset = true;
                lagPrev = 100;
                lastGainIndex = 10;
                prevSignalType = TYPE_NO_VOICE_ACTIVITY;
                Arrays.fill(outBuf, 0);
                Arrays.fill(sLpcQ14Buf, 0);
            }
            fsKHz = fs;
            frameLength = length;
        }
    }

    void decodeIndices(RangeDecoder dec, int frameIndex, boolean decodeLbrr, int condCoding) {
        int ix;
        if (decodeLbrr || vadFlags[frameIndex] != 0) {
            ix = dec.icdf(SilkTables.TYPE_OFFSET_VAD_I_CDF, 8) + 2;
        } else {
            ix = dec.icdf(SilkTables.TYPE_OFFSET_NO_VAD_I_CDF, 8);
        }
        signalType = ix >> 1;
        quantOffsetType = ix & 1;

        if (condCoding == CODE_CONDITIONALLY) {
            gainsIndices[0] = dec.icdf(SilkTables.DELTA_GAIN_I_CDF, 8);
        } else {
            gainsIndices[0] = (byte) (dec.icdf(SilkTables.GAIN_I_CDF, signalType * 8, 8) << 3);
            gainsIndices[0] = (byte) (gainsIndices[0] + dec.icdf(SilkTables.UNIFORM8_I_CDF, 8));
        }
        for (int i = 1; i < nbSubfr; i++) gainsIndices[i] = dec.icdf(SilkTables.DELTA_GAIN_I_CDF, 8);

        nlsfIndices[0] = dec.icdf(nlsfCb.cb1Icdf, (signalType >> 1) * nlsfCb.nVectors, 8);
        int[] ecIx = new int[MAX_LPC_ORDER];
        int[] predQ8 = new int[MAX_LPC_ORDER];
        nlsfUnpack(ecIx, predQ8, nlsfCb, nlsfIndices[0]);
        for (int i = 0; i < nlsfCb.order; i++) {
            ix = dec.icdf(nlsfCb.ecIcdf, ecIx[i], 8);
            if (ix == 0) {
                ix -= dec.icdf(SilkTables.NLSF_EXT_I_CDF, 8);
            } else if (ix == 2 * NLSF_QUANT_MAX_AMPLITUDE) {
                ix += dec.icdf(SilkTables.NLSF_EXT_I_CDF, 8);
            }
            nlsfIndices[i + 1] = ix - NLSF_QUANT_MAX_AMPLITUDE;
        }
        nlsfInterpCoefQ2 = nbSubfr == MAX_NB_SUBFR ? dec.icdf(SilkTables.NLSF_INTERPOLATION_FACTOR_I_CDF, 8) : 4;

        if (signalType == TYPE_VOICED) {
            boolean absolute = true;
            if (condCoding == CODE_CONDITIONALLY && ecPrevSignalType == TYPE_VOICED) {
                int delta = dec.icdf(SilkTables.PITCH_DELTA_I_CDF, 8);
                if (delta > 0) {
                    delta = delta - 9;
                    lagIndex = (short) (ecPrevLagIndex + delta);
                    absolute = false;
                }
            }
            if (absolute) {
                lagIndex = (short) (dec.icdf(SilkTables.PITCH_LAG_I_CDF, 8) * (fsKHz >> 1));
                lagIndex = (short) (lagIndex + dec.icdf(pitchLagLowBitsIcdf, 8));
            }
            ecPrevLagIndex = lagIndex;
            contourIndex = dec.icdf(pitchContourIcdf, 8);
            perIndex = dec.icdf(SilkTables.LTP_PER_INDEX_I_CDF, 8);
            for (int k = 0; k < nbSubfr; k++) ltpIndex[k] = dec.icdf(LTP_GAIN_ICDF[perIndex], 8);
            ltpScaleIndex = condCoding == CODE_INDEPENDENTLY ? dec.icdf(SilkTables.LTPSCALE_I_CDF, 8) : 0;
        }
        ecPrevSignalType = signalType;
        seed = dec.icdf(SilkTables.UNIFORM4_I_CDF, 8);
    }

    // Returns the number of samples written to out[outOff..].
    int decodeFrame(RangeDecoder dec, int[] out, int outOff, int lostFlag, int condCoding) {
        int l = frameLength;
        ltpScaleQ14 = 0;
        if (lostFlag == 0 || (lostFlag == 2 && lbrrFlags[nFramesDecoded] == 1)) {
            int[] pulses = new int[(l + SHELL_CODEC_FRAME_LENGTH - 1) & ~(SHELL_CODEC_FRAME_LENGTH - 1)];
            decodeIndices(dec, nFramesDecoded, lostFlag != 0, condCoding);
            decodePulses(dec, pulses, signalType, quantOffsetType, frameLength);
            decodeParameters(condCoding);
            decodeCore(out, outOff, pulses);
            int mvLen = ltpMemLength - frameLength;
            System.arraycopy(outBuf, frameLength, outBuf, 0, mvLen);
            System.arraycopy(out, outOff, outBuf, mvLen, frameLength);
            lossCnt = 0;
            prevSignalType = signalType;
            firstFrameAfterReset = false;
        } else {
            Arrays.fill(out, outOff, outOff + l, 0);
            for (int k = 0; k < nbSubfr; k++) pitchL[k] = lagPrev;
            int mvLen = ltpMemLength - frameLength;
            System.arraycopy(outBuf, frameLength, outBuf, 0, mvLen);
            System.arraycopy(out, outOff, outBuf, mvLen, frameLength);
            lossCnt++;
        }
        lagPrev = pitchL[nbSubfr - 1];
        return l;
    }

    private void decodeParameters(int condCoding) {
        int[] nlsfQ15 = new int[MAX_LPC_ORDER];
        int[] nlsf0Q15 = new int[MAX_LPC_ORDER];
        lastGainIndex = gainsDequant(gainsQ16, gainsIndices, lastGainIndex, condCoding == CODE_CONDITIONALLY, nbSubfr);
        nlsfDecode(nlsfQ15, nlsfIndices, nlsfCb);
        nlsf2a(predCoefQ12[1], nlsfQ15, lpcOrder);
        if (firstFrameAfterReset) nlsfInterpCoefQ2 = 4;
        if (nlsfInterpCoefQ2 < 4) {
            for (int i = 0; i < lpcOrder; i++) {
                nlsf0Q15[i] = (short) (prevNlsfQ15[i] + ((nlsfInterpCoefQ2 * (nlsfQ15[i] - prevNlsfQ15[i])) >> 2));
            }
            nlsf2a(predCoefQ12[0], nlsf0Q15, lpcOrder);
        } else {
            System.arraycopy(predCoefQ12[1], 0, predCoefQ12[0], 0, lpcOrder);
        }
        System.arraycopy(nlsfQ15, 0, prevNlsfQ15, 0, lpcOrder);
        if (lossCnt != 0) {
            bwexpander(predCoefQ12[0], lpcOrder, BWE_AFTER_LOSS_Q16);
            bwexpander(predCoefQ12[1], lpcOrder, BWE_AFTER_LOSS_Q16);
        }
        if (signalType == TYPE_VOICED) {
            decodePitch(lagIndex, contourIndex, pitchL, fsKHz, nbSubfr);
            int[] cbk = LTP_VQ[perIndex];
            for (int k = 0; k < nbSubfr; k++) {
                int ix = ltpIndex[k];
                for (int i = 0; i < LTP_ORDER; i++) ltpCoefQ14[k * LTP_ORDER + i] = (short) (cbk[ix * LTP_ORDER + i] << 7);
            }
            ltpScaleQ14 = SilkTables.LTPSCALES_TABLE_Q14[ltpScaleIndex];
        } else {
            Arrays.fill(pitchL, 0, nbSubfr, 0);
            Arrays.fill(ltpCoefQ14, 0, LTP_ORDER * nbSubfr, 0);
            perIndex = 0;
            ltpScaleQ14 = 0;
        }
    }

    private void decodeCore(int[] xq, int xqOff, int[] pulses) {
        int[] sLtp = new int[ltpMemLength];
        int[] sLtpQ15 = new int[ltpMemLength + frameLength];
        int[] resQ14 = new int[subfrLength];
        int[] sLpcQ14 = new int[subfrLength + MAX_LPC_ORDER];
        int[] aTmp = new int[MAX_LPC_ORDER];
        int offsetQ10 = QUANTIZATION_OFFSETS_Q10[signalType >> 1][quantOffsetType];
        boolean nlsfInterpolation = nlsfInterpCoefQ2 < 1 << 2;

        int randSeed = seed;
        for (int i = 0; i < frameLength; i++) {
            randSeed = 907633515 + randSeed * 196314165;
            excQ14[i] = pulses[i] << 14;
            if (excQ14[i] > 0) excQ14[i] -= QUANT_LEVEL_ADJUST_Q10 << 4;
            else if (excQ14[i] < 0) excQ14[i] += QUANT_LEVEL_ADJUST_Q10 << 4;
            excQ14[i] += offsetQ10 << 4;
            if (randSeed < 0) excQ14[i] = -excQ14[i];
            randSeed = randSeed + pulses[i];
        }

        System.arraycopy(sLpcQ14Buf, 0, sLpcQ14, 0, MAX_LPC_ORDER);
        int pexc = 0;
        int pxq = xqOff;
        int sLtpBufIdx = ltpMemLength;
        int lag = 0;
        for (int k = 0; k < nbSubfr; k++) {
            int[] aQ12 = predCoefQ12[k >> 1];
            System.arraycopy(aQ12, 0, aTmp, 0, lpcOrder);
            int bOff = k * LTP_ORDER;
            int type = signalType;
            int gainQ10 = gainsQ16[k] >> 6;
            int invGainQ31 = inverse32varQ(gainsQ16[k], 47);
            int gainAdjQ16;
            if (gainsQ16[k] != prevGainQ16) {
                gainAdjQ16 = div32varQ(prevGainQ16, gainsQ16[k], 16);
                for (int i = 0; i < MAX_LPC_ORDER; i++) sLpcQ14[i] = smulww(gainAdjQ16, sLpcQ14[i]);
            } else {
                gainAdjQ16 = 1 << 16;
            }
            prevGainQ16 = gainsQ16[k];

            if (lossCnt != 0 && prevSignalType == TYPE_VOICED && signalType != TYPE_VOICED && k < MAX_NB_SUBFR / 2) {
                Arrays.fill(ltpCoefQ14, bOff, bOff + LTP_ORDER, 0);
                ltpCoefQ14[bOff + LTP_ORDER / 2] = fixConst(0.25, 14);
                type = TYPE_VOICED;
                pitchL[k] = lagPrev;
            }

            int[] res;
            int resOff;
            if (type == TYPE_VOICED) {
                lag = pitchL[k];
                if (k == 0 || (k == 2 && nlsfInterpolation)) {
                    int startIdx = ltpMemLength - lag - lpcOrder - LTP_ORDER / 2;
                    if (k == 2) System.arraycopy(xq, xqOff, outBuf, ltpMemLength, 2 * subfrLength);
                    lpcAnalysisFilter(sLtp, startIdx, outBuf, startIdx + k * subfrLength, aQ12,
                            ltpMemLength - startIdx, lpcOrder);
                    if (k == 0) invGainQ31 = smulwb(invGainQ31, ltpScaleQ14) << 2;
                    for (int i = 0; i < lag + LTP_ORDER / 2; i++) {
                        sLtpQ15[sLtpBufIdx - i - 1] = smulwb(invGainQ31, sLtp[ltpMemLength - i - 1]);
                    }
                } else if (gainAdjQ16 != 1 << 16) {
                    for (int i = 0; i < lag + LTP_ORDER / 2; i++) {
                        sLtpQ15[sLtpBufIdx - i - 1] = smulww(gainAdjQ16, sLtpQ15[sLtpBufIdx - i - 1]);
                    }
                }

                int predLag = sLtpBufIdx - lag + LTP_ORDER / 2;
                for (int i = 0; i < subfrLength; i++) {
                    int ltpPred = 2;
                    ltpPred = smlawb(ltpPred, sLtpQ15[predLag], ltpCoefQ14[bOff]);
                    ltpPred = smlawb(ltpPred, sLtpQ15[predLag - 1], ltpCoefQ14[bOff + 1]);
                    ltpPred = smlawb(ltpPred, sLtpQ15[predLag - 2], ltpCoefQ14[bOff + 2]);
                    ltpPred = smlawb(ltpPred, sLtpQ15[predLag - 3], ltpCoefQ14[bOff + 3]);
                    ltpPred = smlawb(ltpPred, sLtpQ15[predLag - 4], ltpCoefQ14[bOff + 4]);
                    predLag++;
                    resQ14[i] = excQ14[pexc + i] + (ltpPred << 1);
                    sLtpQ15[sLtpBufIdx] = resQ14[i] << 1;
                    sLtpBufIdx++;
                }
                res = resQ14;
                resOff = 0;
            } else {
                res = excQ14;
                resOff = pexc;
            }

            for (int i = 0; i < subfrLength; i++) {
                int lpcPred = lpcOrder >> 1;
                for (int j = 0; j < lpcOrder; j++) lpcPred = smlawb(lpcPred, sLpcQ14[MAX_LPC_ORDER + i - 1 - j], aTmp[j]);
                sLpcQ14[MAX_LPC_ORDER + i] = addSat32(res[resOff + i], lshiftSat32(lpcPred, 4));
                xq[pxq + i] = (short) sat16(rshiftRound(smulww(sLpcQ14[MAX_LPC_ORDER + i], gainQ10), 8));
            }
            System.arraycopy(sLpcQ14, subfrLength, sLpcQ14, 0, MAX_LPC_ORDER);
            pexc += subfrLength;
            pxq += subfrLength;
        }
        System.arraycopy(sLpcQ14, 0, sLpcQ14Buf, 0, MAX_LPC_ORDER);
    }

    static final class Resampler {
        private static final int ORDER_FIR_12 = 8;
        private static final int[][] DELAY_MATRIX_DEC = {
                {4, 0, 2, 0, 0},
                {0, 9, 4, 7, 4},
                {0, 3, 12, 7, 7}
        };
        final int[] sIir = new int[6];
        final int[] sFir = new int[ORDER_FIR_12];
        final int[] delayBuf = new int[48];
        boolean copy;
        int batchSize;
        int invRatioQ16;
        int fsInKHz;
        int fsOutKHz;
        int inputDelay;

        void clear() {
            Arrays.fill(sIir, 0);
            Arrays.fill(sFir, 0);
            Arrays.fill(delayBuf, 0);
            copy = false;
            batchSize = 0;
            invRatioQ16 = 0;
            fsInKHz = 0;
            fsOutKHz = 0;
            inputDelay = 0;
        }

        void copyFrom(Resampler other) {
            System.arraycopy(other.sIir, 0, sIir, 0, sIir.length);
            System.arraycopy(other.sFir, 0, sFir, 0, sFir.length);
            System.arraycopy(other.delayBuf, 0, delayBuf, 0, delayBuf.length);
            copy = other.copy;
            batchSize = other.batchSize;
            invRatioQ16 = other.invRatioQ16;
            fsInKHz = other.fsInKHz;
            fsOutKHz = other.fsOutKHz;
            inputDelay = other.inputDelay;
        }

        private static int rateId(int r) {
            return (((r >> 12) - (r > 16000 ? 1 : 0)) >> (r > 24000 ? 1 : 0)) - 1;
        }

        void init(int fsIn, int fsOut) {
            clear();
            inputDelay = DELAY_MATRIX_DEC[rateId(fsIn)][rateId(fsOut)];
            fsInKHz = fsIn / 1000;
            fsOutKHz = fsOut / 1000;
            batchSize = fsInKHz * 10;
            int up2x = 0;
            if (fsOut > fsIn) {
                up2x = 1;
            } else {
                copy = true;
            }
            invRatioQ16 = ((fsIn << (14 + up2x)) / fsOut) << 2;
            while (smulww(invRatioQ16, fsOut) < fsIn << up2x) invRatioQ16++;
        }

        void process(int[] out, int outOff, int[] in, int inOff, int inLen) {
            int nSamples = fsInKHz - inputDelay;
            System.arraycopy(in, inOff, delayBuf, inputDelay, nSamples);
            if (copy) {
                System.arraycopy(delayBuf, 0, out, outOff, fsInKHz);
                System.arraycopy(in, inOff + nSamples, out, outOff + fsOutKHz, inLen - fsInKHz);
            } else {
                iirFir(out, outOff, delayBuf, 0, fsInKHz);
                iirFir(out, outOff + fsOutKHz, in, inOff + nSamples, inLen - fsInKHz);
            }
            System.arraycopy(in, inOff + inLen - inputDelay, delayBuf, 0, inputDelay);
        }

        private void iirFir(int[] out, int outOff, int[] in, int inOff, int inLen) {
            int[] buf = new int[2 * batchSize + ORDER_FIR_12];
            System.arraycopy(sFir, 0, buf, 0, ORDER_FIR_12);
            int inc = invRatioQ16;
            int nSamplesIn;
            while (true) {
                nSamplesIn = Math.min(inLen, batchSize);
                up2Hq(buf, ORDER_FIR_12, in, inOff, nSamplesIn);
                int maxIndex = nSamplesIn << (16 + 1);
                for (int index = 0; index < maxIndex; index += inc) {
                    int t = smulwb(index & 0xFFFF, 12);
                    int p = index >> 16;
                    int[] fir = SilkTables.RESAMPLER_FRAC_FIR_12;
                    int r = smulbb(buf[p], fir[t * 4]);
                    r = smlabb(r, buf[p + 1], fir[t * 4 + 1]);
                    r = smlabb(r, buf[p + 2], fir[t * 4 + 2]);
                    r = smlabb(r, buf[p + 3], fir[t * 4 + 3]);
                    r = smlabb(r, buf[p + 4], fir[(11 - t) * 4 + 3]);
                    r = smlabb(r, buf[p + 5], fir[(11 - t) * 4 + 2]);
                    r = smlabb(r, buf[p + 6], fir[(11 - t) * 4 + 1]);
                    r = smlabb(r, buf[p + 7], fir[(11 - t) * 4]);
                    out[outOff++] = (short) sat16(rshiftRound(r, 15));
                }
                inOff += nSamplesIn;
                inLen -= nSamplesIn;
                if (inLen > 0) {
                    System.arraycopy(buf, nSamplesIn << 1, buf, 0, ORDER_FIR_12);
                } else {
                    break;
                }
            }
            System.arraycopy(buf, nSamplesIn << 1, sFir, 0, ORDER_FIR_12);
        }

        private void up2Hq(int[] out, int outOff, int[] in, int inOff, int len) {
            int[] s = sIir;
            int[] c0 = SilkTables.RESAMPLER_UP2_HQ_0;
            int[] c1 = SilkTables.RESAMPLER_UP2_HQ_1;
            for (int k = 0; k < len; k++) {
                int in32 = in[inOff + k] << 10;
                int y = in32 - s[0];
                int x = smulwb(y, c0[0]);
                int out1 = s[0] + x;
                s[0] = in32 + x;
                y = out1 - s[1];
                x = smulwb(y, c0[1]);
                int out2 = s[1] + x;
                s[1] = out1 + x;
                y = out2 - s[2];
                x = smlawb(y, y, c0[2]);
                out1 = s[2] + x;
                s[2] = out2 + x;
                out[outOff + 2 * k] = (short) sat16(rshiftRound(out1, 10));

                y = in32 - s[3];
                x = smulwb(y, c1[0]);
                out1 = s[3] + x;
                s[3] = in32 + x;
                y = out1 - s[4];
                x = smulwb(y, c1[1]);
                out2 = s[4] + x;
                s[4] = out1 + x;
                y = out2 - s[5];
                x = smlawb(y, y, c1[2]);
                out1 = s[5] + x;
                s[5] = out2 + x;
                out[outOff + 2 * k + 1] = (short) sat16(rshiftRound(out1, 10));
            }
        }
    }
}
