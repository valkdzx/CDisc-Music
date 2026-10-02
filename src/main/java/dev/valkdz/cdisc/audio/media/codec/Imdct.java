package dev.valkdz.cdisc.audio.media.codec;

// y[n] = scale * sum X[k] cos(2pi/N (n + N/4 + 1/2)(k + 1/2)), N outputs from N/2 inputs.
public final class Imdct {

    private final int n;
    private final int half;
    private final int quarter;
    private final float scale;
    private final float[] preCos;
    private final float[] preSin;
    private final float[] postCos;
    private final float[] postSin;
    private final float[] re;
    private final float[] im;
    private final float[] dct;
    private final float[] fftCos;
    private final float[] fftSin;
    private final int[] bitReverse;

    public Imdct(int n, double scale) {
        if (Integer.bitCount(n) != 1 || n < 16) throw new IllegalArgumentException("IMDCT size " + n);
        this.n = n;
        this.half = n / 2;
        this.quarter = n / 4;
        this.scale = (float) scale;
        preCos = new float[quarter];
        preSin = new float[quarter];
        postCos = new float[quarter];
        postSin = new float[quarter];
        for (int p = 0; p < quarter; p++) {
            double a = -Math.PI * p / half;
            preCos[p] = (float) Math.cos(a);
            preSin[p] = (float) Math.sin(a);
            double b = -Math.PI * (p + 0.25) / half;
            postCos[p] = (float) Math.cos(b);
            postSin[p] = (float) Math.sin(b);
        }
        re = new float[quarter];
        im = new float[quarter];
        dct = new float[half];
        fftCos = new float[quarter / 2];
        fftSin = new float[quarter / 2];
        for (int i = 0; i < quarter / 2; i++) {
            fftCos[i] = (float) Math.cos(-2 * Math.PI * i / quarter);
            fftSin[i] = (float) Math.sin(-2 * Math.PI * i / quarter);
        }
        bitReverse = new int[quarter];
        int bits = Integer.numberOfTrailingZeros(quarter);
        for (int i = 0; i < quarter; i++) bitReverse[i] = Integer.reverse(i) >>> (32 - bits);
    }

    public int size() {
        return n;
    }

    public void transform(float[] in, int inOffset, float[] out, int outOffset) {
        for (int p = 0; p < quarter; p++) {
            float a = in[inOffset + 2 * p];
            float b = in[inOffset + half - 1 - 2 * p];
            int r = bitReverse[p];
            re[r] = a * preCos[p] - b * preSin[p];
            im[r] = a * preSin[p] + b * preCos[p];
        }
        fft();
        for (int j = 0; j < quarter; j++) {
            float a = re[j] * postCos[j] - im[j] * postSin[j];
            float b = re[j] * postSin[j] + im[j] * postCos[j];
            dct[2 * j] = a * scale;
            dct[half - 1 - 2 * j] = -b * scale;
        }
        int m2 = half / 2;
        for (int i = 0; i < m2; i++) out[outOffset + i] = dct[i + m2];
        for (int i = m2; i < 3 * m2; i++) out[outOffset + i] = -dct[3 * m2 - 1 - i];
        for (int i = 3 * m2; i < n; i++) out[outOffset + i] = -dct[i - 3 * m2];
    }

    private void fft() {
        for (int size = 2; size <= quarter; size <<= 1) {
            int step = quarter / size;
            int halfSize = size >> 1;
            for (int start = 0; start < quarter; start += size) {
                for (int k = 0; k < halfSize; k++) {
                    float wr = fftCos[k * step];
                    float wi = fftSin[k * step];
                    int a = start + k;
                    int b = a + halfSize;
                    float tr = re[b] * wr - im[b] * wi;
                    float ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }
}
