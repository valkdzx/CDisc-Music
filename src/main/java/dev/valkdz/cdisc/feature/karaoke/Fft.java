package dev.valkdz.cdisc.feature.karaoke;

final class Fft {

    private Fft() {
    }

    static int size(int atLeast) {
        int n = 1;
        while (n < atLeast) n <<= 1;
        return n;
    }

    // In place, radix 2; re.length must be a power of two. inverse also divides by n.
    static void transform(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = 2 * Math.PI / length * (inverse ? 1 : -1);
            double stepRe = Math.cos(angle);
            double stepIm = Math.sin(angle);
            int half = length >> 1;
            for (int start = 0; start < n; start += length) {
                double wRe = 1;
                double wIm = 0;
                for (int k = 0; k < half; k++) {
                    int a = start + k;
                    int b = a + half;
                    double bRe = re[b] * wRe - im[b] * wIm;
                    double bIm = re[b] * wIm + im[b] * wRe;
                    re[b] = re[a] - bRe;
                    im[b] = im[a] - bIm;
                    re[a] += bRe;
                    im[a] += bIm;
                    double next = wRe * stepRe - wIm * stepIm;
                    wIm = wRe * stepIm + wIm * stepRe;
                    wRe = next;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    record Spectrum(double[] re, double[] im) {

        static Spectrum of(float[] a, int n) {
            double[] re = new double[n];
            double[] im = new double[n];
            for (int i = 0; i < a.length && i < n; i++) re[i] = a[i];
            transform(re, im, false);
            return new Spectrum(re, im);
        }
    }

    // out[lag + maxLag] = sum over i of a[i + lag] * b[i], for lag in [-maxLag, maxLag].
    static double[] correlate(Spectrum a, float[] b, int maxLag) {
        int n = a.re().length;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < b.length && i < n; i++) re[i] = b[i];
        transform(re, im, false);
        for (int k = 0; k < n; k++) {
            double r = a.re()[k] * re[k] + a.im()[k] * im[k];
            double i = a.im()[k] * re[k] - a.re()[k] * im[k];
            re[k] = r;
            im[k] = i;
        }
        transform(re, im, true);
        double[] out = new double[2 * maxLag + 1];
        for (int lag = -maxLag; lag <= maxLag; lag++) out[lag + maxLag] = re[Math.floorMod(lag, n)];
        return out;
    }
}
