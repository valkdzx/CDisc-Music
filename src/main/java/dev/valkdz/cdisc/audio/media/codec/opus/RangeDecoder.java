package dev.valkdz.cdisc.audio.media.codec.opus;

final class RangeDecoder {

    static final int BITRES = 3;
    private static final int SYM_BITS = 8;
    private static final int CODE_BITS = 32;
    private static final long SYM_MAX = (1L << SYM_BITS) - 1;
    private static final long CODE_TOP = 1L << (CODE_BITS - 1);
    private static final long CODE_BOT = CODE_TOP >>> SYM_BITS;
    private static final int CODE_EXTRA = (CODE_BITS - 2) % SYM_BITS + 1;
    private static final int WINDOW_SIZE = 32;
    private static final int UINT_BITS = 8;
    private static final int LAPLACE_MINP = 1;
    private static final int LAPLACE_NMIN = 16;
    private static final long[] CORRECTION = {35733, 38967, 42495, 46340, 50535, 55109, 60097, 65535};

    byte[] buf;
    int base;
    int storage;
    int endOffs;
    long endWindow;
    int nendBits;
    int nbitsTotal;
    int offs;
    long rng;
    long val;
    long ext;
    int rem;
    boolean error;

    void init(byte[] data, int offset, int length) {
        buf = data;
        base = offset;
        storage = length;
        endOffs = 0;
        endWindow = 0;
        nendBits = 0;
        nbitsTotal = CODE_BITS + 1 - ((CODE_BITS - CODE_EXTRA) / SYM_BITS) * SYM_BITS;
        offs = 0;
        rng = 1L << CODE_EXTRA;
        rem = readByte();
        val = rng - 1 - (rem >> (SYM_BITS - CODE_EXTRA));
        error = false;
        normalize();
    }

    private int readByte() {
        return offs < storage ? buf[base + offs++] & 0xFF : 0;
    }

    private int readByteFromEnd() {
        return endOffs < storage ? buf[base + storage - ++endOffs] & 0xFF : 0;
    }

    private void normalize() {
        while (rng <= CODE_BOT) {
            nbitsTotal += SYM_BITS;
            rng = (rng << SYM_BITS) & 0xFFFFFFFFL;
            int sym = rem;
            rem = readByte();
            sym = ((sym << SYM_BITS) | rem) >> (SYM_BITS - CODE_EXTRA);
            val = ((val << SYM_BITS) + (SYM_MAX & ~sym)) & (CODE_TOP - 1);
        }
    }

    int decode(int ft) {
        ext = rng / ft;
        long s = val / ext;
        return (int) (ft - Math.min(s + 1, ft));
    }

    int decodeBin(int bits) {
        ext = rng >>> bits;
        long s = val / ext;
        return (int) ((1L << bits) - Math.min(s + 1, 1L << bits));
    }

    void update(int fl, int fh, int ft) {
        long s = (ext * (ft - fh)) & 0xFFFFFFFFL;
        val -= s;
        rng = fl > 0 ? (ext * (fh - fl)) & 0xFFFFFFFFL : rng - s;
        normalize();
    }

    boolean bitLogp(int logp) {
        long r = rng;
        long d = val;
        long s = r >>> logp;
        boolean ret = d < s;
        if (!ret) val = d - s;
        rng = ret ? s : r - s;
        normalize();
        return ret;
    }

    int icdf(int[] icdf, int offset, int ftb) {
        long s = rng;
        long d = val;
        long r = s >>> ftb;
        int ret = -1;
        long t;
        do {
            t = s;
            s = r * icdf[offset + ++ret];
        } while (d < s);
        val = d - s;
        rng = t - s;
        normalize();
        return ret;
    }

    int icdf(int[] icdf, int ftb) {
        return icdf(icdf, 0, ftb);
    }

    long uint(long ft) {
        ft--;
        int ftb = ilog(ft);
        if (ftb > UINT_BITS) {
            ftb -= UINT_BITS;
            int f = (int) (ft >>> ftb) + 1;
            int s = decode(f);
            update(s, s + 1, f);
            long t = ((long) s << ftb) | bits(ftb);
            if (t <= ft) return t;
            error = true;
            return ft;
        }
        ft++;
        int s = decode((int) ft);
        update(s, s + 1, (int) ft);
        return s;
    }

    int bits(int count) {
        long window = endWindow;
        int available = nendBits;
        if (available < count) {
            do {
                window |= (long) readByteFromEnd() << available;
                available += SYM_BITS;
            } while (available <= WINDOW_SIZE - SYM_BITS);
        }
        int ret = (int) (window & ((1L << count) - 1));
        window >>>= count;
        available -= count;
        endWindow = window;
        nendBits = available;
        nbitsTotal += count;
        return ret;
    }

    int tell() {
        return nbitsTotal - ilog(rng);
    }

    int tellFrac() {
        long nbits = (long) nbitsTotal << BITRES;
        int l = ilog(rng);
        long r = rng >>> (l - 16);
        int b = (int) ((r >>> 12) - 8);
        if (r > CORRECTION[b]) b++;
        l = (l << 3) + b;
        return (int) (nbits - l);
    }

    int laplace(int fs, int decay) {
        int val = 0;
        int fm = decodeBin(15);
        int fl = 0;
        if (fm >= fs) {
            val++;
            fl = fs;
            fs = freq1(fs, decay) + LAPLACE_MINP;
            while (fs > LAPLACE_MINP && fm >= fl + 2 * fs) {
                fs *= 2;
                fl += fs;
                fs = ((fs - 2 * LAPLACE_MINP) * decay) >> 15;
                fs += LAPLACE_MINP;
                val++;
            }
            if (fs <= LAPLACE_MINP) {
                int di = (fm - fl) >> 1;
                val += di;
                fl += 2 * di * LAPLACE_MINP;
            }
            if (fm < fl + fs) {
                val = -val;
            } else {
                fl += fs;
            }
        }
        update(fl, Math.min(fl + fs, 32768), 32768);
        return val;
    }

    private static int freq1(int fs0, int decay) {
        int ft = 32768 - LAPLACE_MINP * (2 * LAPLACE_NMIN) - fs0;
        return (int) (((long) ft * (16384 - decay)) >> 15);
    }

    static int ilog(long value) {
        return value == 0 ? 0 : 64 - Long.numberOfLeadingZeros(value);
    }
}
