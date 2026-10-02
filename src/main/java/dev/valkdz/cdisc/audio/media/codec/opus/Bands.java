package dev.valkdz.cdisc.audio.media.codec.opus;

final class Bands {

    private static final int BITRES = RangeDecoder.BITRES;
    static final int SPREAD_NONE = 0;
    static final int SPREAD_LIGHT = 1;
    static final int SPREAD_NORMAL = 2;
    static final int SPREAD_AGGRESSIVE = 3;
    private static final float EPSILON = 1e-15f;
    private static final int[] SPREAD_FACTOR = {15, 10, 5};
    private static final int[] ORDERY_TABLE = {
            1, 0,
            3, 0, 2, 1,
            7, 0, 4, 3, 6, 1, 5, 2,
            15, 0, 8, 7, 12, 3, 11, 4, 14, 1, 9, 6, 13, 2, 10, 5,
    };
    private static final int[] BIT_INTERLEAVE = {0, 1, 1, 1, 2, 3, 3, 3, 2, 3, 3, 3, 2, 3, 3, 3};
    private static final int[] BIT_DEINTERLEAVE = {
            0x00, 0x03, 0x0C, 0x0F, 0x30, 0x33, 0x3C, 0x3F, 0xC0, 0xC3, 0xCC, 0xCF, 0xF0, 0xF3, 0xFC, 0xFF
    };
    private static final int[] EXP2_TABLE8 = {16384, 17866, 19483, 21247, 23170, 25267, 27554, 30048};

    private final CeltMode m = CeltMode.MODE;
    private int band;
    private int intensity;
    private int spread;
    private int tfChange;
    private RangeDecoder ec;
    private int remainingBits;
    private int seed;
    private boolean disableInv;
    private boolean avoidSplitNoise;

    private int sInv;
    private int sImid;
    private int sIside;
    private int sDelta;
    private int sItheta;
    private int sQalloc;

    static int lcgRand(int seed) {
        return 1664525 * seed + 1013904223;
    }

    private static int fracMul16(int a, int b) {
        return (16384 + (short) a * (short) b) >> 15;
    }

    static int bitexactCos(int x) {
        int tmp = (4096 + x * x) >> 13;
        int x2 = tmp;
        x2 = (32767 - x2) + fracMul16(x2, (-7651 + fracMul16(x2, (8277 + fracMul16(-626, x2)))));
        return 1 + x2;
    }

    static int bitexactLog2tan(int isin, int icos) {
        int lc = RangeDecoder.ilog(icos);
        int ls = RangeDecoder.ilog(isin);
        icos <<= 15 - lc;
        isin <<= 15 - ls;
        return (ls - lc) * (1 << 11)
                + fracMul16(isin, fracMul16(isin, -2597) + 7932)
                - fracMul16(icos, fracMul16(icos, -2597) + 7932);
    }

    // Returns the updated LCG seed.
    int quantAllBands(int start, int end, float[] x, int yOffset, int[] collapseMasks, int[] pulses,
                      int shortBlocks, int spread, int dualStereo, int intensity, int[] tfRes, int totalBits,
                      int balance, RangeDecoder ec, int lm, int codedBands, int seed, boolean disableInv) {
        int[] eBands = m.eBands;
        int mm = 1 << lm;
        int bb = shortBlocks != 0 ? mm : 1;
        int normOffset = mm * eBands[start];
        int c = yOffset >= 0 ? 2 : 1;
        float[] norm = new float[c * (mm * eBands[m.nbEBands - 1] - normOffset)];
        int norm2 = mm * eBands[m.nbEBands - 1] - normOffset;
        float[] scratch = x;
        int scratchOff = mm * eBands[m.effEBands - 1];
        int lowbandOffset = 0;
        boolean updateLowband = true;

        this.ec = ec;
        this.intensity = intensity;
        this.seed = seed;
        this.spread = spread;
        this.disableInv = disableInv;
        this.avoidSplitNoise = bb > 1;

        for (int i = start; i < end; i++) {
            band = i;
            boolean last = i == end - 1;
            float[] xa = x;
            int xo = mm * eBands[i];
            float[] ya = yOffset >= 0 ? x : null;
            int yo = yOffset >= 0 ? yOffset + mm * eBands[i] : 0;
            int n = mm * eBands[i + 1] - mm * eBands[i];
            int tell = ec.tellFrac();

            if (i != start) balance -= tell;
            remainingBits = totalBits - tell - 1;
            int b;
            if (i <= codedBands - 1) {
                int currBalance = sdiv(balance, Math.min(3, codedBands - i));
                b = Math.max(0, Math.min(16383, Math.min(remainingBits + 1, pulses[i] + currBalance)));
            } else {
                b = 0;
            }

            if ((mm * eBands[i] - n >= mm * eBands[start] || i == start + 1) && (updateLowband || lowbandOffset == 0)) {
                lowbandOffset = i;
            }
            if (i == start + 1) specialHybridFolding(norm, norm2, start, mm, dualStereo);

            tfChange = tfRes[i];
            float[] scr = scratch;
            int scrOff = scratchOff;
            if (i >= m.effEBands) {
                xa = norm;
                xo = 0;
                if (ya != null) {
                    ya = norm;
                    yo = 0;
                }
                scr = null;
            }
            if (last) scr = null;

            int effectiveLowband = -1;
            int xcm;
            int ycm;
            if (lowbandOffset != 0 && (spread != SPREAD_AGGRESSIVE || bb > 1 || tfChange < 0)) {
                effectiveLowband = Math.max(0, mm * eBands[lowbandOffset] - normOffset - n);
                int foldStart = lowbandOffset;
                while (mm * eBands[--foldStart] > effectiveLowband + normOffset) {
                }
                int foldEnd = lowbandOffset - 1;
                while (++foldEnd < i && mm * eBands[foldEnd] < effectiveLowband + normOffset + n) {
                }
                xcm = 0;
                ycm = 0;
                int foldI = foldStart;
                do {
                    xcm |= collapseMasks[foldI * c];
                    ycm |= collapseMasks[foldI * c + c - 1];
                } while (++foldI < foldEnd);
            } else {
                xcm = ycm = (1 << bb) - 1;
            }

            if (dualStereo != 0 && i == intensity) {
                dualStereo = 0;
                for (int j = 0; j < mm * eBands[i] - normOffset; j++) norm[j] = .5f * (norm[j] + norm[norm2 + j]);
            }
            float[] lowOut = last ? null : norm;
            int lowOutOff = mm * eBands[i] - normOffset;
            if (dualStereo != 0) {
                xcm = quantBand(xa, xo, n, b / 2, bb, effectiveLowband != -1 ? norm : null, effectiveLowband,
                        lm, lowOut, lowOutOff, 1f, scr, scrOff, xcm);
                ycm = quantBand(ya, yo, n, b / 2, bb, effectiveLowband != -1 ? norm : null, norm2 + effectiveLowband,
                        lm, last ? null : norm, norm2 + lowOutOff, 1f, scr, scrOff, ycm);
            } else {
                if (ya != null) {
                    xcm = quantBandStereo(xa, xo, ya, yo, n, b, bb, effectiveLowband != -1 ? norm : null,
                            effectiveLowband, lm, lowOut, lowOutOff, scr, scrOff, xcm | ycm);
                } else {
                    xcm = quantBand(xa, xo, n, b, bb, effectiveLowband != -1 ? norm : null, effectiveLowband,
                            lm, lowOut, lowOutOff, 1f, scr, scrOff, xcm | ycm);
                }
                ycm = xcm;
            }
            collapseMasks[i * c] = xcm & 0xFF;
            collapseMasks[i * c + c - 1] = ycm & 0xFF;
            balance += pulses[i] + tell;
            updateLowband = b > (n << BITRES);
            avoidSplitNoise = false;
        }
        return this.seed;
    }

    private static int sdiv(int n, int d) {
        return n < 0 ? -Integer.divideUnsigned(-n, d) : Integer.divideUnsigned(n, d);
    }

    private void specialHybridFolding(float[] norm, int norm2, int start, int mm, int dualStereo) {
        int[] eBands = m.eBands;
        int n1 = mm * (eBands[start + 1] - eBands[start]);
        int n2 = mm * (eBands[start + 2] - eBands[start + 1]);
        if (n2 - n1 > 0) System.arraycopy(norm, 2 * n1 - n2, norm, n1, n2 - n1);
        if (dualStereo != 0 && n2 - n1 > 0) System.arraycopy(norm, norm2 + 2 * n1 - n2, norm, norm2 + n1, n2 - n1);
    }

    private static int computeQn(int n, int b, int offset, int pulseCap, boolean stereo) {
        int n2 = 2 * n - 1;
        if (stereo && n == 2) n2--;
        int qb = sdiv(b + n2 * offset, n2);
        qb = Math.min(b - pulseCap - (4 << BITRES), qb);
        qb = Math.min(8 << BITRES, qb);
        int qn;
        if (qb < (1 << BITRES >> 1)) {
            qn = 1;
        } else {
            qn = EXP2_TABLE8[qb & 0x7] >> (14 - (qb >> BITRES));
            qn = (qn + 1) >> 1 << 1;
        }
        return qn;
    }

    // Returns the new fill and leaves the split in the s* fields; b is passed and returned through sQalloc.
    private int computeTheta(int n, int[] b, int bb, int b0, int lm, boolean stereo, int fill) {
        int pulseCap = m.logN[band] + lm * (1 << BITRES);
        int offset = (pulseCap >> 1) - (stereo && n == 2 ? Rate.QTHETA_OFFSET_TWOPHASE : Rate.QTHETA_OFFSET);
        int qn = computeQn(n, b[0], offset, pulseCap, stereo);
        if (stereo && band >= intensity) qn = 1;
        int tell = ec.tellFrac();
        int itheta = 0;
        int inv = 0;
        if (qn != 1) {
            if (stereo && n > 2) {
                int p0 = 3;
                int x0 = qn / 2;
                int ft = p0 * (x0 + 1) + x0;
                int fs = ec.decode(ft);
                int x = fs < (x0 + 1) * p0 ? fs / p0 : x0 + 1 + (fs - (x0 + 1) * p0);
                ec.update(x <= x0 ? p0 * x : (x - 1 - x0) + (x0 + 1) * p0,
                        x <= x0 ? p0 * (x + 1) : (x - x0) + (x0 + 1) * p0, ft);
                itheta = x;
            } else if (b0 > 1 || stereo) {
                itheta = (int) ec.uint(qn + 1);
            } else {
                int ft = ((qn >> 1) + 1) * ((qn >> 1) + 1);
                int fm = ec.decode(ft);
                int fs;
                int fl;
                if (fm < ((qn >> 1) * ((qn >> 1) + 1) >> 1)) {
                    itheta = (isqrt32(8L * fm + 1) - 1) >> 1;
                    fs = itheta + 1;
                    fl = itheta * (itheta + 1) >> 1;
                } else {
                    itheta = (2 * (qn + 1) - isqrt32(8L * (ft - fm - 1) + 1)) >> 1;
                    fs = qn + 1 - itheta;
                    fl = ft - ((qn + 1 - itheta) * (qn + 2 - itheta) >> 1);
                }
                ec.update(fl, fl + fs, ft);
            }
            itheta = Integer.divideUnsigned(itheta * 16384, qn);
        } else if (stereo) {
            if (b[0] > 2 << BITRES && remainingBits > 2 << BITRES) {
                inv = ec.bitLogp(2) ? 1 : 0;
            } else {
                inv = 0;
            }
            if (disableInv) inv = 0;
            itheta = 0;
        }
        int qalloc = ec.tellFrac() - tell;
        b[0] -= qalloc;

        int imid;
        int iside;
        int delta;
        if (itheta == 0) {
            imid = 32767;
            iside = 0;
            fill &= (1 << bb) - 1;
            delta = -16384;
        } else if (itheta == 16384) {
            imid = 0;
            iside = 32767;
            fill &= ((1 << bb) - 1) << bb;
            delta = 16384;
        } else {
            imid = bitexactCos(itheta);
            iside = bitexactCos(16384 - itheta);
            delta = fracMul16((n - 1) << 7, bitexactLog2tan(iside, imid));
        }
        sInv = inv;
        sImid = imid;
        sIside = iside;
        sDelta = delta;
        sItheta = itheta;
        sQalloc = qalloc;
        return fill;
    }

    static int isqrt32(long val) {
        int g = 0;
        int bshift = (RangeDecoder.ilog(val) - 1) >> 1;
        long b = 1L << bshift;
        do {
            long t = (((long) g << 1) + b) << bshift;
            if (t <= val) {
                g += (int) b;
                val -= t;
            }
            b >>= 1;
            bshift--;
        } while (bshift >= 0);
        return g;
    }

    private int quantBandN1(float[] x, int xo, float[] y, int yo, float[] lowOut, int loo) {
        boolean stereo = y != null;
        float[] a = x;
        int ao = xo;
        for (int c = 0; c < (stereo ? 2 : 1); c++) {
            int sign = 0;
            if (remainingBits >= 1 << BITRES) {
                sign = ec.bits(1);
                remainingBits -= 1 << BITRES;
            }
            a[ao] = sign != 0 ? -1f : 1f;
            a = y;
            ao = yo;
        }
        if (lowOut != null) lowOut[loo] = x[xo];
        return 1;
    }

    private int quantPartition(float[] x, int xo, int n, int b, int bb, float[] low, int lo, int lm, float gain, int fill) {
        int b0 = bb;
        int cm = 0;
        int cache = m.cacheIndex[(lm + 1) * m.nbEBands + band];
        if (lm != -1 && b > m.cacheBits[cache + m.cacheBits[cache]] + 12 && n > 2) {
            n >>= 1;
            int yo = xo + n;
            lm -= 1;
            if (bb == 1) fill = (fill & 1) | (fill << 1);
            bb = (bb + 1) >> 1;

            int[] bRef = {b};
            fill = computeTheta(n, bRef, bb, b0, lm, false, fill);
            b = bRef[0];
            int imid = sImid;
            int iside = sIside;
            int delta = sDelta;
            int itheta = sItheta;
            int qalloc = sQalloc;
            float mid = (1.f / 32768) * imid;
            float side = (1.f / 32768) * iside;

            if (b0 > 1 && (itheta & 0x3fff) != 0) {
                if (itheta > 8192) {
                    delta -= delta >> (4 - lm);
                } else {
                    delta = Math.min(0, delta + (n << BITRES >> (5 - lm)));
                }
            }
            int mbits = Math.max(0, Math.min(b, (b - delta) / 2));
            int sbits = b - mbits;
            remainingBits -= qalloc;

            float[] low2 = low;
            int lo2 = low != null ? lo + n : 0;

            int rebalance = remainingBits;
            if (mbits >= sbits) {
                cm = quantPartition(x, xo, n, mbits, bb, low, lo, lm, gain * mid, fill);
                rebalance = mbits - (rebalance - remainingBits);
                if (rebalance > 3 << BITRES && itheta != 0) sbits += rebalance - (3 << BITRES);
                cm |= quantPartition(x, yo, n, sbits, bb, low2, lo2, lm, gain * side, fill >> bb) << (b0 >> 1);
            } else {
                cm = quantPartition(x, yo, n, sbits, bb, low2, lo2, lm, gain * side, fill >> bb) << (b0 >> 1);
                rebalance = sbits - (rebalance - remainingBits);
                if (rebalance > 3 << BITRES && itheta != 16384) mbits += rebalance - (3 << BITRES);
                cm |= quantPartition(x, xo, n, mbits, bb, low, lo, lm, gain * mid, fill);
            }
        } else {
            int q = Rate.bits2pulses(m, band, lm, b);
            int currBits = Rate.pulses2bits(m, band, lm, q);
            remainingBits -= currBits;
            while (remainingBits < 0 && q > 0) {
                remainingBits += currBits;
                q--;
                currBits = Rate.pulses2bits(m, band, lm, q);
                remainingBits -= currBits;
            }
            if (q != 0) {
                int k = Rate.getPulses(q);
                cm = algUnquant(x, xo, n, k, spread, bb, gain);
            } else {
                int cmMask = (int) ((1L << bb) - 1);
                fill &= cmMask;
                if (fill == 0) {
                    for (int j = 0; j < n; j++) x[xo + j] = 0;
                } else {
                    if (low == null) {
                        for (int j = 0; j < n; j++) {
                            seed = lcgRand(seed);
                            x[xo + j] = (float) (seed >> 20);
                        }
                        cm = cmMask;
                    } else {
                        for (int j = 0; j < n; j++) {
                            seed = lcgRand(seed);
                            float tmp = 1.0f / 256;
                            tmp = (seed & 0x8000) != 0 ? tmp : -tmp;
                            x[xo + j] = low[lo + j] + tmp;
                        }
                        cm = fill;
                    }
                    renormaliseVector(x, xo, n, gain);
                }
            }
        }
        return cm;
    }

    private int quantBand(float[] x, int xo, int n, int b, int bb, float[] low, int lo, int lm,
                          float[] lowOut, int loo, float gain, float[] scratch, int so, int fill) {
        int n0 = n;
        int nb = n;
        int b0 = bb;
        int timeDivide = 0;
        int recombine = 0;
        boolean longBlocks = b0 == 1;
        int cm;
        int tf = tfChange;

        nb = Integer.divideUnsigned(nb, bb);
        if (n == 1) return quantBandN1(x, xo, null, 0, lowOut, loo);

        if (tf > 0) recombine = tf;
        if (scratch != null && low != null && (recombine != 0 || ((nb & 1) == 0 && tf < 0) || b0 > 1)) {
            System.arraycopy(low, lo, scratch, so, n);
            low = scratch;
            lo = so;
        }
        for (int k = 0; k < recombine; k++) {
            if (low != null) haar1(low, lo, n >> k, 1 << k);
            fill = BIT_INTERLEAVE[fill & 0xF] | BIT_INTERLEAVE[fill >> 4] << 2;
        }
        bb >>= recombine;
        nb <<= recombine;

        while ((nb & 1) == 0 && tf < 0) {
            if (low != null) haar1(low, lo, nb, bb);
            fill |= fill << bb;
            bb <<= 1;
            nb >>= 1;
            timeDivide++;
            tf++;
        }
        b0 = bb;
        int nb0 = nb;

        if (b0 > 1 && low != null) deinterleaveHadamard(low, lo, nb >> recombine, b0 << recombine, longBlocks);

        cm = quantPartition(x, xo, n, b, bb, low, lo, lm, gain, fill);

        if (b0 > 1) interleaveHadamard(x, xo, nb >> recombine, b0 << recombine, longBlocks);
        nb = nb0;
        bb = b0;
        for (int k = 0; k < timeDivide; k++) {
            bb >>= 1;
            nb <<= 1;
            cm |= cm >>> bb;
            haar1(x, xo, nb, bb);
        }
        for (int k = 0; k < recombine; k++) {
            cm = BIT_DEINTERLEAVE[cm];
            haar1(x, xo, n0 >> k, 1 << k);
        }
        bb <<= recombine;

        if (lowOut != null) {
            float nScale = (float) Math.sqrt(n0);
            for (int j = 0; j < n0; j++) lowOut[loo + j] = nScale * x[xo + j];
        }
        cm &= (1 << bb) - 1;
        return cm;
    }

    private int quantBandStereo(float[] x, int xo, float[] y, int yo, int n, int b, int bb, float[] low, int lo, int lm,
                                float[] lowOut, int loo, float[] scratch, int so, int fill) {
        if (n == 1) return quantBandN1(x, xo, y, yo, lowOut, loo);
        int origFill = fill;
        int[] bRef = {b};
        fill = computeTheta(n, bRef, bb, bb, lm, true, fill);
        b = bRef[0];
        int inv = sInv;
        int imid = sImid;
        int iside = sIside;
        int delta = sDelta;
        int itheta = sItheta;
        int qalloc = sQalloc;
        float mid = (1.f / 32768) * imid;
        float side = (1.f / 32768) * iside;
        int cm;

        if (n == 2) {
            int mbits = b;
            int sbits = 0;
            if (itheta != 0 && itheta != 16384) sbits = 1 << BITRES;
            mbits -= sbits;
            boolean c = itheta > 8192;
            remainingBits -= qalloc + sbits;
            float[] x2 = c ? y : x;
            int x2o = c ? yo : xo;
            float[] y2 = c ? x : y;
            int y2o = c ? xo : yo;
            int sign = 0;
            if (sbits != 0) sign = ec.bits(1);
            sign = 1 - 2 * sign;
            cm = quantBand(x2, x2o, n, mbits, bb, low, lo, lm, lowOut, loo, 1f, scratch, so, origFill);
            y2[y2o] = -sign * x2[x2o + 1];
            y2[y2o + 1] = sign * x2[x2o];
            x[xo] = mid * x[xo];
            x[xo + 1] = mid * x[xo + 1];
            y[yo] = side * y[yo];
            y[yo + 1] = side * y[yo + 1];
            float tmp = x[xo];
            x[xo] = tmp - y[yo];
            y[yo] = tmp + y[yo];
            tmp = x[xo + 1];
            x[xo + 1] = tmp - y[yo + 1];
            y[yo + 1] = tmp + y[yo + 1];
        } else {
            int mbits = Math.max(0, Math.min(b, (b - delta) / 2));
            int sbits = b - mbits;
            remainingBits -= qalloc;
            int rebalance = remainingBits;
            if (mbits >= sbits) {
                cm = quantBand(x, xo, n, mbits, bb, low, lo, lm, lowOut, loo, 1f, scratch, so, fill);
                rebalance = mbits - (rebalance - remainingBits);
                if (rebalance > 3 << BITRES && itheta != 0) sbits += rebalance - (3 << BITRES);
                cm |= quantBand(y, yo, n, sbits, bb, null, 0, lm, null, 0, side, null, 0, fill >> bb);
            } else {
                cm = quantBand(y, yo, n, sbits, bb, null, 0, lm, null, 0, side, null, 0, fill >> bb);
                rebalance = sbits - (rebalance - remainingBits);
                if (rebalance > 3 << BITRES && itheta != 16384) mbits += rebalance - (3 << BITRES);
                cm |= quantBand(x, xo, n, mbits, bb, low, lo, lm, lowOut, loo, 1f, scratch, so, fill);
            }
        }

        if (n != 2) stereoMerge(x, xo, y, yo, mid, n);
        if (inv != 0) {
            for (int j = 0; j < n; j++) y[yo + j] = -y[yo + j];
        }
        return cm;
    }

    private static void stereoMerge(float[] x, int xo, float[] y, int yo, float mid, int n) {
        float xp = 0;
        float side = 0;
        for (int i = 0; i < n; i++) {
            xp += y[yo + i] * x[xo + i];
            side += y[yo + i] * y[yo + i];
        }
        xp = mid * xp;
        float mid2 = mid;
        float el = mid2 * mid2 + side - 2 * xp;
        float er = mid2 * mid2 + side + 2 * xp;
        if (er < 6e-4f || el < 6e-4f) {
            System.arraycopy(x, xo, y, yo, n);
            return;
        }
        float lgain = (float) (1.0 / Math.sqrt(el));
        float rgain = (float) (1.0 / Math.sqrt(er));
        for (int j = 0; j < n; j++) {
            float l = mid * x[xo + j];
            float r = y[yo + j];
            x[xo + j] = lgain * (l - r);
            y[yo + j] = rgain * (l + r);
        }
    }

    static void haar1(float[] x, int xo, int n0, int stride) {
        n0 >>= 1;
        for (int i = 0; i < stride; i++) {
            for (int j = 0; j < n0; j++) {
                float tmp1 = .70710678f * x[xo + stride * 2 * j + i];
                float tmp2 = .70710678f * x[xo + stride * (2 * j + 1) + i];
                x[xo + stride * 2 * j + i] = tmp1 + tmp2;
                x[xo + stride * (2 * j + 1) + i] = tmp1 - tmp2;
            }
        }
    }

    private static void deinterleaveHadamard(float[] x, int xo, int n0, int stride, boolean hadamard) {
        int n = n0 * stride;
        float[] tmp = new float[n];
        if (hadamard) {
            int ordery = stride - 2;
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) tmp[ORDERY_TABLE[ordery + i] * n0 + j] = x[xo + j * stride + i];
            }
        } else {
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) tmp[i * n0 + j] = x[xo + j * stride + i];
            }
        }
        System.arraycopy(tmp, 0, x, xo, n);
    }

    private static void interleaveHadamard(float[] x, int xo, int n0, int stride, boolean hadamard) {
        int n = n0 * stride;
        float[] tmp = new float[n];
        if (hadamard) {
            int ordery = stride - 2;
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) tmp[j * stride + i] = x[xo + ORDERY_TABLE[ordery + i] * n0 + j];
            }
        } else {
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) tmp[j * stride + i] = x[xo + i * n0 + j];
            }
        }
        System.arraycopy(tmp, 0, x, xo, n);
    }

    static void renormaliseVector(float[] x, int xo, int n, float gain) {
        float e = EPSILON;
        for (int i = 0; i < n; i++) e += x[xo + i] * x[xo + i];
        float g = (float) (1.0 / Math.sqrt(e)) * gain;
        for (int i = 0; i < n; i++) x[xo + i] = g * x[xo + i];
    }

    private int algUnquant(float[] x, int xo, int n, int k, int spread, int bb, float gain) {
        int[] iy = new int[n];
        float ryy = decodePulses(iy, n, k);
        float g = (float) (1.0 / Math.sqrt(ryy)) * gain;
        for (int i = 0; i < n; i++) x[xo + i] = g * iy[i];
        expRotation(x, xo, n, -1, bb, k, spread);
        return extractCollapseMask(iy, n, bb);
    }

    private static int extractCollapseMask(int[] iy, int n, int bb) {
        if (bb <= 1) return 1;
        int n0 = Integer.divideUnsigned(n, bb);
        int mask = 0;
        for (int i = 0; i < bb; i++) {
            int tmp = 0;
            for (int j = 0; j < n0; j++) tmp |= iy[i * n0 + j];
            if (tmp != 0) mask |= 1 << i;
        }
        return mask;
    }

    static void expRotation(float[] x, int xo, int len, int dir, int stride, int k, int spread) {
        if (2 * k >= len || spread == SPREAD_NONE) return;
        int factor = SPREAD_FACTOR[spread - 1];
        float gain = (float) len / (float) (len + factor * k);
        float theta = .5f * (gain * gain);
        float c = (float) Math.cos(.5f * (float) Math.PI * theta);
        float s = (float) Math.cos(.5f * (float) Math.PI * (1f - theta));
        int stride2 = 0;
        if (len >= 8 * stride) {
            stride2 = 1;
            while ((stride2 * stride2 + stride2) * stride + (stride >> 2) < len) stride2++;
        }
        len = Integer.divideUnsigned(len, stride);
        for (int i = 0; i < stride; i++) {
            if (dir < 0) {
                if (stride2 != 0) expRotation1(x, xo + i * len, len, stride2, s, c);
                expRotation1(x, xo + i * len, len, 1, c, s);
            } else {
                expRotation1(x, xo + i * len, len, 1, c, -s);
                if (stride2 != 0) expRotation1(x, xo + i * len, len, stride2, s, -c);
            }
        }
    }

    private static void expRotation1(float[] x, int xo, int len, int stride, float c, float s) {
        float ms = -s;
        int p = xo;
        for (int i = 0; i < len - stride; i++) {
            float x1 = x[p];
            float x2 = x[p + stride];
            x[p + stride] = c * x2 + s * x1;
            x[p++] = c * x1 + ms * x2;
        }
        p = xo + len - 2 * stride - 1;
        for (int i = len - 2 * stride - 1; i >= 0; i--) {
            float x1 = x[p];
            float x2 = x[p + stride];
            x[p + stride] = c * x2 + s * x1;
            x[p--] = c * x1 + ms * x2;
        }
    }

    private float decodePulses(int[] y, int n, int k) {
        long[] u = new long[k + 2];
        long v = ncwrsUrow(n, k, u);
        long i = ec.uint(v);
        return cwrsi(n, k, i, y, u);
    }

    private static long ncwrsUrow(int n, int k, long[] u) {
        int len = k + 2;
        u[0] = 0;
        u[1] = 1;
        for (int j = 2; j < len; j++) u[j] = (j << 1) - 1;
        for (int j = 2; j < n; j++) unext(u, 1, k + 1, 1);
        return (u[k] + u[k + 1]) & 0xFFFFFFFFL;
    }

    private static void unext(long[] ui, int off, int len, long ui0) {
        int j = 1;
        do {
            long ui1 = (ui[off + j] + ui[off + j - 1] + ui0) & 0xFFFFFFFFL;
            ui[off + j - 1] = ui0;
            ui0 = ui1;
        } while (++j < len);
        ui[off + j - 1] = ui0;
    }

    private static void uprev(long[] ui, int n, long ui0) {
        int j = 1;
        do {
            long ui1 = (ui[j] - ui[j - 1] - ui0) & 0xFFFFFFFFL;
            ui[j - 1] = ui0;
            ui0 = ui1;
        } while (++j < n);
        ui[j - 1] = ui0;
    }

    private static float cwrsi(int n, int k, long i, int[] y, long[] u) {
        float yy = 0;
        int j = 0;
        do {
            long p = u[k + 1];
            int s = i >= p ? -1 : 0;
            if (s != 0) i -= p;
            int yj = k;
            p = u[k];
            while (p > i) p = u[--k];
            i -= p;
            yj -= k;
            int val = (yj + s) ^ s;
            y[j] = val;
            yy += val * val;
            uprev(u, k + 2, 0);
        } while (++j < n);
        return yy;
    }
}
