package dev.valkdz.cdisc.audio.media.codec.opus;

final class Rate {

    static final int MAX_FINE_BITS = 8;
    static final int FINE_OFFSET = 21;
    static final int QTHETA_OFFSET = 4;
    static final int QTHETA_OFFSET_TWOPHASE = 16;
    static final int LOG_MAX_PSEUDO = 6;
    private static final int ALLOC_STEPS = 6;
    private static final int BITRES = RangeDecoder.BITRES;
    private static final int[] LOG2_FRAC_TABLE = {
            0, 8, 13, 16, 19, 21, 23, 24, 26, 27, 28, 29, 30, 31, 32, 32, 33, 34, 34, 35, 36, 36, 37, 37
    };

    private Rate() {
    }

    static int getPulses(int i) {
        return i < 8 ? i : (8 + (i & 7)) << ((i >> 3) - 1);
    }

    static int bits2pulses(CeltMode m, int band, int lm, int bits) {
        lm++;
        int cache = m.cacheIndex[lm * m.nbEBands + band];
        int lo = 0;
        int hi = m.cacheBits[cache];
        bits--;
        for (int i = 0; i < LOG_MAX_PSEUDO; i++) {
            int mid = (lo + hi + 1) >> 1;
            if (m.cacheBits[cache + mid] >= bits) hi = mid;
            else lo = mid;
        }
        int low = lo == 0 ? -1 : m.cacheBits[cache + lo];
        return bits - low <= m.cacheBits[cache + hi] - bits ? lo : hi;
    }

    static int pulses2bits(CeltMode m, int band, int lm, int pulses) {
        lm++;
        int cache = m.cacheIndex[lm * m.nbEBands + band];
        return pulses == 0 ? 0 : m.cacheBits[cache + pulses] + 1;
    }

    static final class Allocation {
        int codedBands;
        int intensity;
        int dualStereo;
        int balance;
    }

    static void compute(CeltMode m, int start, int end, int[] offsets, int[] cap, int allocTrim,
                        int total, int[] pulses, int[] ebits, int[] finePriority, int c, int lm,
                        RangeDecoder ec, Allocation out) {
        total = Math.max(total, 0);
        int len = m.nbEBands;
        int skipStart = start;
        int skipRsv = total >= 1 << BITRES ? 1 << BITRES : 0;
        total -= skipRsv;
        int intensityRsv = 0;
        int dualStereoRsv = 0;
        if (c == 2) {
            intensityRsv = LOG2_FRAC_TABLE[end - start];
            if (intensityRsv > total) {
                intensityRsv = 0;
            } else {
                total -= intensityRsv;
                dualStereoRsv = total >= 1 << BITRES ? 1 << BITRES : 0;
                total -= dualStereoRsv;
            }
        }
        int[] bits1 = new int[len];
        int[] bits2 = new int[len];
        int[] thresh = new int[len];
        int[] trimOffset = new int[len];
        for (int j = start; j < end; j++) {
            int width = m.eBands[j + 1] - m.eBands[j];
            thresh[j] = Math.max(c << BITRES, (3 * width << lm << BITRES) >> 4);
            trimOffset[j] = c * width * (allocTrim - 5 - lm) * (end - j - 1) * (1 << (lm + BITRES)) >> 6;
            if (width << lm == 1) trimOffset[j] -= c << BITRES;
        }
        int lo = 1;
        int hi = m.nbAllocVectors - 1;
        do {
            boolean done = false;
            int psum = 0;
            int mid = (lo + hi) >> 1;
            for (int j = end; j-- > start; ) {
                int n = m.eBands[j + 1] - m.eBands[j];
                int bitsj = c * n * m.allocVectors[mid * len + j] << lm >> 2;
                if (bitsj > 0) bitsj = Math.max(0, bitsj + trimOffset[j]);
                bitsj += offsets[j];
                if (bitsj >= thresh[j] || done) {
                    done = true;
                    psum += Math.min(bitsj, cap[j]);
                } else if (bitsj >= c << BITRES) {
                    psum += c << BITRES;
                }
            }
            if (psum > total) hi = mid - 1;
            else lo = mid + 1;
        } while (lo <= hi);
        hi = lo--;
        for (int j = start; j < end; j++) {
            int n = m.eBands[j + 1] - m.eBands[j];
            int bits1j = c * n * m.allocVectors[lo * len + j] << lm >> 2;
            int bits2j = hi >= m.nbAllocVectors ? cap[j] : c * n * m.allocVectors[hi * len + j] << lm >> 2;
            if (bits1j > 0) bits1j = Math.max(0, bits1j + trimOffset[j]);
            if (bits2j > 0) bits2j = Math.max(0, bits2j + trimOffset[j]);
            if (lo > 0) bits1j += offsets[j];
            bits2j += offsets[j];
            if (offsets[j] > 0) skipStart = j;
            bits2j = Math.max(0, bits2j - bits1j);
            bits1[j] = bits1j;
            bits2[j] = bits2j;
        }
        interpBits2Pulses(m, start, end, skipStart, bits1, bits2, thresh, cap, total, skipRsv, intensityRsv,
                dualStereoRsv, pulses, ebits, finePriority, c, lm, ec, out);
    }

    private static void interpBits2Pulses(CeltMode m, int start, int end, int skipStart, int[] bits1, int[] bits2,
                                          int[] thresh, int[] cap, int total, int skipRsv, int intensityRsv,
                                          int dualStereoRsv, int[] bits, int[] ebits, int[] finePriority, int c,
                                          int lm, RangeDecoder ec, Allocation out) {
        int allocFloor = c << BITRES;
        int stereo = c > 1 ? 1 : 0;
        int logM = lm << BITRES;
        int lo = 0;
        int hi = 1 << ALLOC_STEPS;
        int psum;
        for (int i = 0; i < ALLOC_STEPS; i++) {
            int mid = (lo + hi) >> 1;
            psum = 0;
            boolean done = false;
            for (int j = end; j-- > start; ) {
                int tmp = bits1[j] + (mid * bits2[j] >> ALLOC_STEPS);
                if (tmp >= thresh[j] || done) {
                    done = true;
                    psum += Math.min(tmp, cap[j]);
                } else if (tmp >= allocFloor) {
                    psum += allocFloor;
                }
            }
            if (psum > total) hi = mid;
            else lo = mid;
        }
        psum = 0;
        boolean done = false;
        for (int j = end; j-- > start; ) {
            int tmp = bits1[j] + (lo * bits2[j] >> ALLOC_STEPS);
            if (tmp < thresh[j] && !done) {
                tmp = tmp >= allocFloor ? allocFloor : 0;
            } else {
                done = true;
            }
            tmp = Math.min(tmp, cap[j]);
            bits[j] = tmp;
            psum += tmp;
        }

        int codedBands;
        for (codedBands = end; ; codedBands--) {
            int j = codedBands - 1;
            if (j <= skipStart) {
                total += skipRsv;
                break;
            }
            int left = total - psum;
            int percoeff = Integer.divideUnsigned(left, m.eBands[codedBands] - m.eBands[start]);
            left -= (m.eBands[codedBands] - m.eBands[start]) * percoeff;
            int rem = Math.max(left - (m.eBands[j] - m.eBands[start]), 0);
            int bandWidth = m.eBands[codedBands] - m.eBands[j];
            int bandBits = bits[j] + percoeff * bandWidth + rem;
            if (bandBits >= Math.max(thresh[j], allocFloor + (1 << BITRES))) {
                if (ec.bitLogp(1)) break;
                psum += 1 << BITRES;
                bandBits -= 1 << BITRES;
            }
            psum -= bits[j] + intensityRsv;
            if (intensityRsv > 0) intensityRsv = LOG2_FRAC_TABLE[j - start];
            psum += intensityRsv;
            if (bandBits >= allocFloor) {
                psum += allocFloor;
                bits[j] = allocFloor;
            } else {
                bits[j] = 0;
            }
        }

        if (intensityRsv > 0) {
            out.intensity = start + (int) ec.uint(codedBands + 1 - start);
        } else {
            out.intensity = 0;
        }
        if (out.intensity <= start) {
            total += dualStereoRsv;
            dualStereoRsv = 0;
        }
        out.dualStereo = dualStereoRsv > 0 && ec.bitLogp(1) ? 1 : 0;

        int left = total - psum;
        int percoeff = Integer.divideUnsigned(left, m.eBands[codedBands] - m.eBands[start]);
        left -= (m.eBands[codedBands] - m.eBands[start]) * percoeff;
        for (int j = start; j < codedBands; j++) bits[j] += percoeff * (m.eBands[j + 1] - m.eBands[j]);
        for (int j = start; j < codedBands; j++) {
            int tmp = Math.min(left, m.eBands[j + 1] - m.eBands[j]);
            bits[j] += tmp;
            left -= tmp;
        }

        int balance = 0;
        int j;
        for (j = start; j < codedBands; j++) {
            int n0 = m.eBands[j + 1] - m.eBands[j];
            int n = n0 << lm;
            int bit = bits[j] + balance;
            int excess;
            if (n > 1) {
                excess = Math.max(bit - cap[j], 0);
                bits[j] = bit - excess;
                int den = c * n + ((c == 2 && n > 2 && out.dualStereo == 0 && j < out.intensity) ? 1 : 0);
                int nclogn = den * (m.logN[j] + logM);
                int offset = (nclogn >> 1) - den * FINE_OFFSET;
                if (n == 2) offset += den << BITRES >> 2;
                if (bits[j] + offset < den * 2 << BITRES) offset += nclogn >> 2;
                else if (bits[j] + offset < den * 3 << BITRES) offset += nclogn >> 3;
                ebits[j] = Math.max(0, bits[j] + offset + (den << (BITRES - 1)));
                ebits[j] = Integer.divideUnsigned(ebits[j], den) >> BITRES;
                if (c * ebits[j] > (bits[j] >> BITRES)) ebits[j] = bits[j] >> stereo >> BITRES;
                ebits[j] = Math.min(ebits[j], MAX_FINE_BITS);
                finePriority[j] = ebits[j] * (den << BITRES) >= bits[j] + offset ? 1 : 0;
                bits[j] -= c * ebits[j] << BITRES;
            } else {
                excess = Math.max(0, bit - (c << BITRES));
                bits[j] = bit - excess;
                ebits[j] = 0;
                finePriority[j] = 1;
            }
            if (excess > 0) {
                int extraFine = Math.min(excess >> (stereo + BITRES), MAX_FINE_BITS - ebits[j]);
                ebits[j] += extraFine;
                int extraBits = extraFine * c << BITRES;
                finePriority[j] = extraBits >= excess - balance ? 1 : 0;
                excess -= extraBits;
            }
            balance = excess;
        }
        out.balance = balance;
        for (; j < end; j++) {
            ebits[j] = bits[j] >> stereo >> BITRES;
            bits[j] = 0;
            finePriority[j] = ebits[j] < 1 ? 1 : 0;
        }
        out.codedBands = codedBands;
    }
}
