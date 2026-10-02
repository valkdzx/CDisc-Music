package dev.valkdz.cdisc.audio.media.codec.opus;

import java.util.Arrays;

import static dev.valkdz.cdisc.audio.media.codec.opus.Silk.*;

final class SilkDecoder {

    static final class Control {
        int nChannelsApi;
        int nChannelsInternal;
        int apiSampleRate;
        int internalSampleRate;
        int payloadSizeMs;
    }

    private final SilkChannel[] channels = {new SilkChannel(), new SilkChannel()};
    private final int[] predPrevQ13 = new int[2];
    private final int[] sMid = new int[2];
    private final int[] sSide = new int[2];
    private int nChannelsApi;
    private int nChannelsInternal;
    private int prevDecodeOnlyMiddle;

    SilkDecoder() {
        reset();
    }

    void reset() {
        for (SilkChannel channel : channels) channel.reset();
        Arrays.fill(predPrevQ13, 0);
        Arrays.fill(sMid, 0);
        Arrays.fill(sSide, 0);
        prevDecodeOnlyMiddle = 0;
    }

    // Writes interleaved 16-bit samples at the API rate into out[outOff..]; frameSize[0] gets the count per channel.
    int decode(Control control, int lostFlag, boolean newPacket, RangeDecoder dec, short[] out, int outOff, int[] frameSize) {
        int decodeOnlyMiddle = 0;
        int[] msPredQ13 = new int[2];
        SilkChannel[] ch = channels;

        if (newPacket) {
            for (int n = 0; n < control.nChannelsInternal; n++) ch[n].nFramesDecoded = 0;
        }
        if (control.nChannelsInternal > nChannelsInternal) ch[1].reset();

        boolean stereoToMono = control.nChannelsInternal == 1 && nChannelsInternal == 2
                && control.internalSampleRate == 1000 * ch[0].fsKHz;

        if (ch[0].nFramesDecoded == 0) {
            for (int n = 0; n < control.nChannelsInternal; n++) {
                switch (control.payloadSizeMs) {
                    case 0, 10 -> {
                        ch[n].nFramesPerPacket = 1;
                        ch[n].nbSubfr = 2;
                    }
                    case 20 -> {
                        ch[n].nFramesPerPacket = 1;
                        ch[n].nbSubfr = 4;
                    }
                    case 40 -> {
                        ch[n].nFramesPerPacket = 2;
                        ch[n].nbSubfr = 4;
                    }
                    case 60 -> {
                        ch[n].nFramesPerPacket = 3;
                        ch[n].nbSubfr = 4;
                    }
                    default -> {
                        return -203;
                    }
                }
                int fsKHzDec = (control.internalSampleRate >> 10) + 1;
                if (fsKHzDec != 8 && fsKHzDec != 12 && fsKHzDec != 16) return -200;
                ch[n].setFs(fsKHzDec, control.apiSampleRate);
            }
        }

        if (control.nChannelsApi == 2 && control.nChannelsInternal == 2 && (nChannelsApi == 1 || nChannelsInternal == 1)) {
            Arrays.fill(predPrevQ13, 0);
            Arrays.fill(sSide, 0);
            ch[1].resampler.copyFrom(ch[0].resampler);
        }
        nChannelsApi = control.nChannelsApi;
        nChannelsInternal = control.nChannelsInternal;

        if (lostFlag != 1 && ch[0].nFramesDecoded == 0) {
            for (int n = 0; n < control.nChannelsInternal; n++) {
                for (int i = 0; i < ch[n].nFramesPerPacket; i++) ch[n].vadFlags[i] = dec.bitLogp(1) ? 1 : 0;
                ch[n].lbrrFlag = dec.bitLogp(1) ? 1 : 0;
            }
            for (int n = 0; n < control.nChannelsInternal; n++) {
                Arrays.fill(ch[n].lbrrFlags, 0);
                if (ch[n].lbrrFlag != 0) {
                    if (ch[n].nFramesPerPacket == 1) {
                        ch[n].lbrrFlags[0] = 1;
                    } else {
                        int[] icdf = ch[n].nFramesPerPacket == 2 ? SilkTables.LBRR_FLAGS_2_I_CDF : SilkTables.LBRR_FLAGS_3_I_CDF;
                        int symbol = dec.icdf(icdf, 8) + 1;
                        for (int i = 0; i < ch[n].nFramesPerPacket; i++) ch[n].lbrrFlags[i] = (symbol >> i) & 1;
                    }
                }
            }
            if (lostFlag == 0) {
                for (int i = 0; i < ch[0].nFramesPerPacket; i++) {
                    for (int n = 0; n < control.nChannelsInternal; n++) {
                        if (ch[n].lbrrFlags[i] == 0) continue;
                        int[] pulses = new int[MAX_FRAME_LENGTH];
                        if (control.nChannelsInternal == 2 && n == 0) {
                            stereoDecodePred(dec, msPredQ13);
                            if (ch[1].lbrrFlags[i] == 0) decodeOnlyMiddle = dec.icdf(SilkTables.STEREO_ONLY_CODE_MID_I_CDF, 8);
                        }
                        int condCoding = i > 0 && ch[n].lbrrFlags[i - 1] != 0 ? CODE_CONDITIONALLY : CODE_INDEPENDENTLY;
                        ch[n].decodeIndices(dec, i, true, condCoding);
                        decodePulses(dec, pulses, ch[n].signalType, ch[n].quantOffsetType, ch[n].frameLength);
                    }
                }
            }
        }

        if (control.nChannelsInternal == 2) {
            if (lostFlag == 0 || (lostFlag == 2 && ch[0].lbrrFlags[ch[0].nFramesDecoded] == 1)) {
                stereoDecodePred(dec, msPredQ13);
                if ((lostFlag == 0 && ch[1].vadFlags[ch[0].nFramesDecoded] == 0)
                        || (lostFlag == 2 && ch[1].lbrrFlags[ch[0].nFramesDecoded] == 0)) {
                    decodeOnlyMiddle = dec.icdf(SilkTables.STEREO_ONLY_CODE_MID_I_CDF, 8);
                } else {
                    decodeOnlyMiddle = 0;
                }
            } else {
                msPredQ13[0] = predPrevQ13[0];
                msPredQ13[1] = predPrevQ13[1];
            }
        }

        if (control.nChannelsInternal == 2 && decodeOnlyMiddle == 0 && prevDecodeOnlyMiddle == 1) {
            Arrays.fill(ch[1].outBuf, 0);
            Arrays.fill(ch[1].sLpcQ14Buf, 0);
            ch[1].lagPrev = 100;
            ch[1].lastGainIndex = 10;
            ch[1].prevSignalType = TYPE_NO_VOICE_ACTIVITY;
            ch[1].firstFrameAfterReset = true;
        }

        int frameLength = ch[0].frameLength;
        int[][] tmp = new int[2][frameLength + 2];
        boolean hasSide;
        if (lostFlag == 0) {
            hasSide = decodeOnlyMiddle == 0;
        } else {
            hasSide = prevDecodeOnlyMiddle == 0
                    || (control.nChannelsInternal == 2 && lostFlag == 2 && ch[1].lbrrFlags[ch[1].nFramesDecoded] == 1);
        }

        int nSamplesOutDec = 0;
        for (int n = 0; n < control.nChannelsInternal; n++) {
            if (n == 0 || hasSide) {
                int frameIndex = ch[0].nFramesDecoded - n;
                int condCoding;
                if (frameIndex <= 0) {
                    condCoding = CODE_INDEPENDENTLY;
                } else if (lostFlag == 2) {
                    condCoding = ch[n].lbrrFlags[frameIndex - 1] != 0 ? CODE_CONDITIONALLY : CODE_INDEPENDENTLY;
                } else if (n > 0 && prevDecodeOnlyMiddle != 0) {
                    condCoding = CODE_INDEPENDENTLY_NO_LTP_SCALING;
                } else {
                    condCoding = CODE_CONDITIONALLY;
                }
                nSamplesOutDec = ch[n].decodeFrame(dec, tmp[n], 2, lostFlag, condCoding);
            } else {
                Arrays.fill(tmp[n], 2, 2 + nSamplesOutDec, 0);
            }
            ch[n].nFramesDecoded++;
        }

        if (control.nChannelsApi == 2 && control.nChannelsInternal == 2) {
            msToLr(tmp[0], tmp[1], msPredQ13, ch[0].fsKHz, nSamplesOutDec);
        } else {
            tmp[0][0] = sMid[0];
            tmp[0][1] = sMid[1];
            sMid[0] = tmp[0][nSamplesOutDec];
            sMid[1] = tmp[0][nSamplesOutDec + 1];
        }

        int nSamplesOut = nSamplesOutDec * control.apiSampleRate / smulbb(ch[0].fsKHz, 1000);
        int[] resampled = new int[nSamplesOut];
        int apiChannels = control.nChannelsApi;
        for (int n = 0; n < Math.min(control.nChannelsApi, control.nChannelsInternal); n++) {
            ch[n].resampler.process(resampled, 0, tmp[n], 1, nSamplesOutDec);
            for (int i = 0; i < nSamplesOut; i++) out[outOff + n + apiChannels * i] = (short) resampled[i];
        }
        if (control.nChannelsApi == 2 && control.nChannelsInternal == 1) {
            if (stereoToMono) {
                ch[1].resampler.process(resampled, 0, tmp[0], 1, nSamplesOutDec);
                for (int i = 0; i < nSamplesOut; i++) out[outOff + 1 + 2 * i] = (short) resampled[i];
            } else {
                for (int i = 0; i < nSamplesOut; i++) out[outOff + 1 + 2 * i] = out[outOff + 2 * i];
            }
        }

        if (lostFlag == 1) {
            for (int i = 0; i < nChannelsInternal; i++) ch[i].lastGainIndex = 10;
        } else {
            prevDecodeOnlyMiddle = decodeOnlyMiddle;
        }
        frameSize[0] = nSamplesOut;
        return 0;
    }

    private void msToLr(int[] x1, int[] x2, int[] predQ13, int fsKHz, int frameLength) {
        x1[0] = sMid[0];
        x1[1] = sMid[1];
        x2[0] = sSide[0];
        x2[1] = sSide[1];
        sMid[0] = x1[frameLength];
        sMid[1] = x1[frameLength + 1];
        sSide[0] = x2[frameLength];
        sSide[1] = x2[frameLength + 1];

        int pred0 = predPrevQ13[0];
        int pred1 = predPrevQ13[1];
        int denomQ16 = (1 << 16) / (STEREO_INTERP_LEN_MS * fsKHz);
        int delta0 = rshiftRound(smulbb(predQ13[0] - predPrevQ13[0], denomQ16), 16);
        int delta1 = rshiftRound(smulbb(predQ13[1] - predPrevQ13[1], denomQ16), 16);
        int n;
        for (n = 0; n < STEREO_INTERP_LEN_MS * fsKHz; n++) {
            pred0 += delta0;
            pred1 += delta1;
            int sum = ((x1[n] + x1[n + 2]) + (x1[n + 1] << 1)) << 9;
            sum = smlawb(x2[n + 1] << 8, sum, pred0);
            sum = smlawb(sum, x1[n + 1] << 11, pred1);
            x2[n + 1] = (short) sat16(rshiftRound(sum, 8));
        }
        pred0 = predQ13[0];
        pred1 = predQ13[1];
        for (; n < frameLength; n++) {
            int sum = ((x1[n] + x1[n + 2]) + (x1[n + 1] << 1)) << 9;
            sum = smlawb(x2[n + 1] << 8, sum, pred0);
            sum = smlawb(sum, x1[n + 1] << 11, pred1);
            x2[n + 1] = (short) sat16(rshiftRound(sum, 8));
        }
        predPrevQ13[0] = predQ13[0];
        predPrevQ13[1] = predQ13[1];
        for (n = 0; n < frameLength; n++) {
            int sum = x1[n + 1] + x2[n + 1];
            int diff = x1[n + 1] - x2[n + 1];
            x1[n + 1] = (short) sat16(sum);
            x2[n + 1] = (short) sat16(diff);
        }
    }
}
