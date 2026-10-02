package dev.valkdz.cdisc.audio.media.codec.opus;

final class CeltMode {

    static final CeltMode MODE = new CeltMode();

    final int fs = 48000;
    final int overlap = 120;
    final int nbEBands = 21;
    final int effEBands = 21;
    final float preemph0 = 0.85000610f;
    final int[] eBands = CeltTables.EBANDS;
    final int maxLM = 3;
    final int nbShortMdcts = 8;
    final int shortMdctSize = 120;
    final int nbAllocVectors = 11;
    final int[] allocVectors = CeltTables.BAND_ALLOCATION;
    final int[] logN = CeltTables.LOG_N;
    final float[] window = new float[120];
    final int mdctN = 1920;
    final int mdctMaxShift = 3;
    final KissFft[] kfft = new KissFft[4];
    final float[] trig;
    final int[] cacheIndex = CeltTables.CACHE_INDEX;
    final int[] cacheBits = CeltTables.CACHE_BITS;
    final int[] cacheCaps = CeltTables.CACHE_CAPS;

    private CeltMode() {
        for (int i = 0; i < overlap; i++) {
            double s = Math.sin(.5 * Math.PI * (i + .5) / overlap);
            window[i] = (float) Math.sin(.5 * Math.PI * s * s);
        }
        kfft[0] = new KissFft(mdctN >> 2, null);
        for (int i = 1; i <= mdctMaxShift; i++) kfft[i] = new KissFft(mdctN >> 2 >> i, kfft[0]);
        int n = mdctN;
        int n2 = n >> 1;
        trig = new float[mdctN - (n2 >> mdctMaxShift)];
        int at = 0;
        for (int shift = 0; shift <= mdctMaxShift; shift++) {
            for (int i = 0; i < n2; i++) trig[at + i] = (float) Math.cos(2 * Math.PI * (i + .125) / n);
            at += n2;
            n2 >>= 1;
            n >>= 1;
        }
    }

    // Reads in[inOff + k*stride], writes N/2+overlap samples at out[outOff]; the FFT runs in place there.
    void mdctBackward(float[] in, int inOff, float[] out, int outOff, int shift, int stride) {
        int n = mdctN;
        int t = 0;
        for (int i = 0; i < shift; i++) {
            n >>= 1;
            t += n;
        }
        int n2 = n >> 1;
        int n4 = n >> 2;
        KissFft fft = kfft[shift];

        int xp1 = inOff;
        int xp2 = inOff + stride * (n2 - 1);
        int yp = outOff + (overlap >> 1);
        for (int i = 0; i < n4; i++) {
            int rev = fft.bitrev[i];
            float x1 = in[xp1];
            float x2 = in[xp2];
            float yr = x2 * trig[t + i] + x1 * trig[t + n4 + i];
            float yi = x1 * trig[t + i] - x2 * trig[t + n4 + i];
            out[yp + 2 * rev + 1] = yr;
            out[yp + 2 * rev] = yi;
            xp1 += 2 * stride;
            xp2 -= 2 * stride;
        }

        fft.impl(out, yp);

        int yp0 = outOff + (overlap >> 1);
        int yp1 = outOff + (overlap >> 1) + n2 - 2;
        for (int i = 0; i < (n4 + 1) >> 1; i++) {
            float re = out[yp0 + 1];
            float im = out[yp0];
            float t0 = trig[t + i];
            float t1 = trig[t + n4 + i];
            float yr = re * t0 + im * t1;
            float yi = re * t1 - im * t0;
            re = out[yp1 + 1];
            im = out[yp1];
            out[yp0] = yr;
            out[yp1 + 1] = yi;
            t0 = trig[t + n4 - i - 1];
            t1 = trig[t + n2 - i - 1];
            yr = re * t0 + im * t1;
            yi = re * t1 - im * t0;
            out[yp1] = yr;
            out[yp0 + 1] = yi;
            yp0 += 2;
            yp1 -= 2;
        }

        int x = outOff + overlap - 1;
        int y = outOff;
        int wp1 = 0;
        int wp2 = overlap - 1;
        for (int i = 0; i < overlap / 2; i++) {
            float x1 = out[x];
            float x2 = out[y];
            out[y++] = window[wp2] * x2 - window[wp1] * x1;
            out[x--] = window[wp1] * x2 + window[wp2] * x1;
            wp1++;
            wp2--;
        }
    }
}
