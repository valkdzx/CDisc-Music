package dev.valkdz.cdisc.audio.media.codec.opus;

final class KissFft {

    final int nfft;
    final float scale;
    final int shift;
    final int[] factors = new int[16];
    final int[] bitrev;
    private final float[] twr;
    private final float[] twi;

    KissFft(int nfft, KissFft base) {
        this.nfft = nfft;
        this.scale = 1f / nfft;
        if (base == null) {
            twr = new float[nfft];
            twi = new float[nfft];
            for (int i = 0; i < nfft; i++) {
                double phase = (-2 * Math.PI / nfft) * i;
                twr[i] = (float) Math.cos(phase);
                twi[i] = (float) Math.sin(phase);
            }
            shift = -1;
        } else {
            twr = base.twr;
            twi = base.twi;
            int s = 0;
            while (s < 32 && nfft << s != base.nfft) s++;
            shift = s;
        }
        factor(nfft);
        bitrev = new int[nfft];
        bitrevTable(0, 0, 1, 1, 0);
    }

    private void factor(int n) {
        int p = 4;
        int stages = 0;
        int nbak = n;
        do {
            while (n % p != 0) {
                switch (p) {
                    case 4 -> p = 2;
                    case 2 -> p = 3;
                    default -> p += 2;
                }
                if (p > 32000 || p * p > n) p = n;
            }
            n /= p;
            if (p > 5) throw new IllegalArgumentException("FFT size " + nbak);
            factors[2 * stages] = p;
            if (p == 2 && stages > 1) {
                factors[2 * stages] = 4;
                factors[2] = 2;
            }
            stages++;
        } while (n > 1);
        n = nbak;
        for (int i = 0; i < stages / 2; i++) {
            int tmp = factors[2 * i];
            factors[2 * i] = factors[2 * (stages - i - 1)];
            factors[2 * (stages - i - 1)] = tmp;
        }
        for (int i = 0; i < stages; i++) {
            n /= factors[2 * i];
            factors[2 * i + 1] = n;
        }
    }

    private int bitrevTable(int fout, int f, int fstride, int inStride, int fac) {
        int p = factors[fac];
        int m = factors[fac + 1];
        if (m == 1) {
            for (int j = 0; j < p; j++) {
                bitrev[f] = fout + j;
                f += fstride * inStride;
            }
        } else {
            for (int j = 0; j < p; j++) {
                bitrevTable(fout, f, fstride * p, inStride, fac + 2);
                f += fstride * inStride;
                fout += m;
            }
        }
        return f;
    }

    void impl(float[] f, int base) {
        int[] fstride = new int[9];
        int sh = Math.max(shift, 0);
        fstride[0] = 1;
        int l = 0;
        int m;
        do {
            int p = factors[2 * l];
            m = factors[2 * l + 1];
            fstride[l + 1] = fstride[l] * p;
            l++;
        } while (m != 1);
        m = factors[2 * l - 1];
        for (int i = l - 1; i >= 0; i--) {
            int m2 = i != 0 ? factors[2 * i - 1] : 1;
            switch (factors[2 * i]) {
                case 2 -> bfly2(f, base, m, fstride[i]);
                case 4 -> bfly4(f, base, fstride[i] << sh, m, fstride[i], m2);
                case 3 -> bfly3(f, base, fstride[i] << sh, m, fstride[i], m2);
                case 5 -> bfly5(f, base, fstride[i] << sh, m, fstride[i], m2);
                default -> throw new IllegalStateException();
            }
            m = m2;
        }
    }

    private static void bfly2(float[] f, int base, int m, int n) {
        float tw = 0.7071067812f;
        int fo = base;
        for (int i = 0; i < n; i++) {
            int f2 = fo + 8;
            float tr = f[f2];
            float ti = f[f2 + 1];
            f[f2] = f[fo] - tr;
            f[f2 + 1] = f[fo + 1] - ti;
            f[fo] += tr;
            f[fo + 1] += ti;

            tr = (f[f2 + 2] + f[f2 + 3]) * tw;
            ti = (f[f2 + 3] - f[f2 + 2]) * tw;
            f[f2 + 2] = f[fo + 2] - tr;
            f[f2 + 3] = f[fo + 3] - ti;
            f[fo + 2] += tr;
            f[fo + 3] += ti;

            tr = f[f2 + 5];
            ti = -f[f2 + 4];
            f[f2 + 4] = f[fo + 4] - tr;
            f[f2 + 5] = f[fo + 5] - ti;
            f[fo + 4] += tr;
            f[fo + 5] += ti;

            tr = (f[f2 + 7] - f[f2 + 6]) * tw;
            ti = -(f[f2 + 7] + f[f2 + 6]) * tw;
            f[f2 + 6] = f[fo + 6] - tr;
            f[f2 + 7] = f[fo + 7] - ti;
            f[fo + 6] += tr;
            f[fo + 7] += ti;
            fo += 16;
        }
    }

    private void bfly4(float[] f, int base, int fstride, int m, int n, int mm) {
        if (m == 1) {
            int fo = base;
            for (int i = 0; i < n; i++) {
                float s0r = f[fo] - f[fo + 4];
                float s0i = f[fo + 1] - f[fo + 5];
                f[fo] += f[fo + 4];
                f[fo + 1] += f[fo + 5];
                float s1r = f[fo + 2] + f[fo + 6];
                float s1i = f[fo + 3] + f[fo + 7];
                f[fo + 4] = f[fo] - s1r;
                f[fo + 5] = f[fo + 1] - s1i;
                f[fo] += s1r;
                f[fo + 1] += s1i;
                s1r = f[fo + 2] - f[fo + 6];
                s1i = f[fo + 3] - f[fo + 7];
                f[fo + 2] = s0r + s1i;
                f[fo + 3] = s0i - s1r;
                f[fo + 6] = s0r - s1i;
                f[fo + 7] = s0i + s1r;
                fo += 8;
            }
            return;
        }
        int m2 = 2 * m;
        int m3 = 3 * m;
        for (int i = 0; i < n; i++) {
            int fo = base + 2 * i * mm;
            int tw1 = 0;
            int tw2 = 0;
            int tw3 = 0;
            for (int j = 0; j < m; j++) {
                int a = fo + 2 * m;
                int b = fo + 2 * m2;
                int c = fo + 2 * m3;
                float s0r = f[a] * twr[tw1] - f[a + 1] * twi[tw1];
                float s0i = f[a] * twi[tw1] + f[a + 1] * twr[tw1];
                float s1r = f[b] * twr[tw2] - f[b + 1] * twi[tw2];
                float s1i = f[b] * twi[tw2] + f[b + 1] * twr[tw2];
                float s2r = f[c] * twr[tw3] - f[c + 1] * twi[tw3];
                float s2i = f[c] * twi[tw3] + f[c + 1] * twr[tw3];
                float s5r = f[fo] - s1r;
                float s5i = f[fo + 1] - s1i;
                f[fo] += s1r;
                f[fo + 1] += s1i;
                float s3r = s0r + s2r;
                float s3i = s0i + s2i;
                float s4r = s0r - s2r;
                float s4i = s0i - s2i;
                f[b] = f[fo] - s3r;
                f[b + 1] = f[fo + 1] - s3i;
                tw1 += fstride;
                tw2 += fstride * 2;
                tw3 += fstride * 3;
                f[fo] += s3r;
                f[fo + 1] += s3i;
                f[a] = s5r + s4i;
                f[a + 1] = s5i - s4r;
                f[c] = s5r - s4i;
                f[c + 1] = s5i + s4r;
                fo += 2;
            }
        }
    }

    private void bfly3(float[] f, int base, int fstride, int m, int n, int mm) {
        int m2 = 2 * m;
        float epi3i = twi[fstride * m];
        for (int i = 0; i < n; i++) {
            int fo = base + 2 * i * mm;
            int tw1 = 0;
            int tw2 = 0;
            int k = m;
            do {
                int a = fo + 2 * m;
                int b = fo + 2 * m2;
                float s1r = f[a] * twr[tw1] - f[a + 1] * twi[tw1];
                float s1i = f[a] * twi[tw1] + f[a + 1] * twr[tw1];
                float s2r = f[b] * twr[tw2] - f[b + 1] * twi[tw2];
                float s2i = f[b] * twi[tw2] + f[b + 1] * twr[tw2];
                float s3r = s1r + s2r;
                float s3i = s1i + s2i;
                float s0r = s1r - s2r;
                float s0i = s1i - s2i;
                tw1 += fstride;
                tw2 += fstride * 2;
                f[a] = f[fo] - s3r * .5f;
                f[a + 1] = f[fo + 1] - s3i * .5f;
                s0r *= epi3i;
                s0i *= epi3i;
                f[fo] += s3r;
                f[fo + 1] += s3i;
                f[b] = f[a] + s0i;
                f[b + 1] = f[a + 1] - s0r;
                f[a] += -s0i;
                f[a + 1] += s0r;
                fo += 2;
            } while (--k != 0);
        }
    }

    private void bfly5(float[] f, int base, int fstride, int m, int n, int mm) {
        float yar = twr[fstride * m];
        float yai = twi[fstride * m];
        float ybr = twr[fstride * 2 * m];
        float ybi = twi[fstride * 2 * m];
        for (int i = 0; i < n; i++) {
            int f0 = base + 2 * i * mm;
            int f1 = f0 + 2 * m;
            int f2 = f0 + 4 * m;
            int f3 = f0 + 6 * m;
            int f4 = f0 + 8 * m;
            for (int u = 0; u < m; u++) {
                float s0r = f[f0];
                float s0i = f[f0 + 1];
                int t1 = u * fstride;
                int t2 = 2 * u * fstride;
                int t3 = 3 * u * fstride;
                int t4 = 4 * u * fstride;
                float s1r = f[f1] * twr[t1] - f[f1 + 1] * twi[t1];
                float s1i = f[f1] * twi[t1] + f[f1 + 1] * twr[t1];
                float s2r = f[f2] * twr[t2] - f[f2 + 1] * twi[t2];
                float s2i = f[f2] * twi[t2] + f[f2 + 1] * twr[t2];
                float s3r = f[f3] * twr[t3] - f[f3 + 1] * twi[t3];
                float s3i = f[f3] * twi[t3] + f[f3 + 1] * twr[t3];
                float s4r = f[f4] * twr[t4] - f[f4 + 1] * twi[t4];
                float s4i = f[f4] * twi[t4] + f[f4 + 1] * twr[t4];

                float s7r = s1r + s4r;
                float s7i = s1i + s4i;
                float s10r = s1r - s4r;
                float s10i = s1i - s4i;
                float s8r = s2r + s3r;
                float s8i = s2i + s3i;
                float s9r = s2r - s3r;
                float s9i = s2i - s3i;

                f[f0] = f[f0] + (s7r + s8r);
                f[f0 + 1] = f[f0 + 1] + (s7i + s8i);

                float s5r = s0r + (s7r * yar + s8r * ybr);
                float s5i = s0i + (s7i * yar + s8i * ybr);
                float s6r = s10i * yai + s9i * ybi;
                float s6i = -(s10r * yai + s9r * ybi);

                f[f1] = s5r - s6r;
                f[f1 + 1] = s5i - s6i;
                f[f4] = s5r + s6r;
                f[f4 + 1] = s5i + s6i;

                float s11r = s0r + (s7r * ybr + s8r * yar);
                float s11i = s0i + (s7i * ybr + s8i * yar);
                float s12r = s9i * yai - s10i * ybi;
                float s12i = s10r * ybi - s9r * yai;

                f[f2] = s11r + s12r;
                f[f2 + 1] = s11i + s12i;
                f[f3] = s11r - s12r;
                f[f3 + 1] = s11i - s12i;

                f0 += 2;
                f1 += 2;
                f2 += 2;
                f3 += 2;
                f4 += 2;
            }
        }
    }
}
