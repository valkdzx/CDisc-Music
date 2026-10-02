package dev.valkdz.cdisc.audio.media;

public final class Resampler {

    private static final int HALF_TAPS = 16;
    private static final int MAX_PHASES = 4096;
    private static final double KAISER_BETA = 9.0;

    private final int up;
    private final int down;
    private final int half;
    private final float[][] phases;
    private float[] buffer = new float[8192];
    private int filled;
    private long consumed;
    private long outIndex;

    public Resampler(int inRate, int outRate) {
        int g = gcd(inRate, outRate);
        int l = outRate / g;
        int m = inRate / g;
        if (l > MAX_PHASES) {
            m = (int) Math.round((double) m * MAX_PHASES / l);
            l = MAX_PHASES;
        }
        up = l;
        down = m;
        double cutoff = Math.min(1.0, (double) l / m) * 0.97;
        half = (int) Math.ceil(HALF_TAPS / Math.min(1.0, (double) l / m));
        int taps = 2 * half;
        phases = new float[l][taps];
        double i0Beta = bessel(KAISER_BETA);
        for (int p = 0; p < l; p++) {
            double frac = (double) p / l;
            double sum = 0;
            for (int j = 0; j < taps; j++) {
                double x = j - half + 1 - frac;
                double sinc = x == 0 ? 1.0 : Math.sin(Math.PI * cutoff * x) / (Math.PI * cutoff * x);
                double w = x / half;
                double window = Math.abs(w) >= 1 ? 0 : bessel(KAISER_BETA * Math.sqrt(1 - w * w)) / i0Beta;
                double v = sinc * window * cutoff;
                phases[p][j] = (float) v;
                sum += v;
            }
            for (int j = 0; j < taps; j++) phases[p][j] /= (float) sum;
        }
        reset();
    }

    public void reset() {
        filled = half - 1;
        java.util.Arrays.fill(buffer, 0, filled, 0f);
        consumed = 0;
        outIndex = 0;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static double bessel(double x) {
        double sum = 1;
        double term = 1;
        for (int k = 1; k < 60; k++) {
            term *= (x / (2 * k)) * (x / (2 * k));
            sum += term;
            if (term < sum * 1e-14) break;
        }
        return sum;
    }

    public int latency() {
        return half;
    }

    public int maxOutput(int inputSamples) {
        return (int) ((long) (inputSamples + 2) * up / down) + 2;
    }

    // Feeds n input samples and writes every output sample that is now ready; returns how many.
    public int process(float[] in, int offset, int n, float[] out, int outOffset) {
        if (filled + n > buffer.length) {
            float[] bigger = new float[Math.max(buffer.length * 2, filled + n)];
            System.arraycopy(buffer, 0, bigger, 0, filled);
            buffer = bigger;
        }
        System.arraycopy(in, offset, buffer, filled, n);
        filled += n;

        int written = 0;
        int taps = 2 * half;
        while (true) {
            long t = outIndex * down;
            long center = t / up;
            int phase = (int) (t % up);
            long first = center - half + 1 + (half - 1);
            int start = (int) (first - consumed);
            if (start + taps > filled) break;
            float[] h = phases[phase];
            float acc = 0;
            for (int j = 0; j < taps; j++) acc += buffer[start + j] * h[j];
            out[outOffset + written++] = acc;
            outIndex++;
        }

        long keepFrom = (outIndex * down) / up;
        int drop = (int) Math.max(0, keepFrom - consumed);
        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, filled - drop);
            filled -= drop;
            consumed += drop;
        }
        return written;
    }
}
