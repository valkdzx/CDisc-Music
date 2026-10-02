package dev.valkdz.cdisc.audio.media.codec;

import dev.valkdz.cdisc.audio.media.AudioDecoder;
import dev.valkdz.cdisc.audio.media.Packet;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class VorbisDecoder implements AudioDecoder {

    private static final float[] INVERSE_DB = VorbisTables.INVERSE_DB;
    private static final int[] FLOOR1_RANGE = {256, 128, 86, 64};

    private final int channels;
    private final int outChannels;
    private final int sampleRate;
    private final int[] blocksize = new int[2];
    private final Codebook[] codebooks;
    private final Floor1[] floors;
    private final Residue[] residues;
    private final Mapping[] mappings;
    private final Mode[] modes;
    private final Imdct[] imdct = new Imdct[2];
    private final float[][][] windows = new float[2][][];

    private final Bits in = new Bits();
    private final float[][] block;
    private final float[][] previous;
    private int previousN;
    private final int[][] finalY;
    private final boolean[] zero;
    private final boolean[] reallyZero;

    public VorbisDecoder(List<byte[]> headers) throws IOException {
        if (headers.size() < 3) throw new IOException("Vorbis needs three header packets");
        byte[] id = headers.get(0);
        Bits h = new Bits().reset(id, 0, id.length);
        if (h.read(8) != 1) throw new IOException("Bad Vorbis identification header");
        h.skipBytes(6);
        if (h.read(32) != 0) throw new IOException("Unsupported Vorbis version");
        channels = h.read(8);
        sampleRate = h.read(32);
        h.read(32);
        h.read(32);
        h.read(32);
        blocksize[0] = 1 << h.read(4);
        blocksize[1] = 1 << h.read(4);
        if (channels < 1 || sampleRate <= 0 || blocksize[0] < 64 || blocksize[1] < blocksize[0] || blocksize[1] > 8192) {
            throw new IOException("Bad Vorbis identification header");
        }
        outChannels = Math.min(channels, 2);

        byte[] setup = headers.get(2);
        Bits s = new Bits().reset(setup, 0, setup.length);
        if (s.read(8) != 5) throw new IOException("Bad Vorbis setup header");
        s.skipBytes(6);

        codebooks = new Codebook[s.read(8) + 1];
        for (int i = 0; i < codebooks.length; i++) codebooks[i] = new Codebook(s);

        int times = s.read(6) + 1;
        for (int i = 0; i < times; i++) {
            if (s.read(16) != 0) throw new IOException("Bad Vorbis time domain transform");
        }

        floors = new Floor1[s.read(6) + 1];
        for (int i = 0; i < floors.length; i++) {
            int type = s.read(16);
            if (type != 1) throw new IOException("Vorbis floor type " + type + " is not supported");
            floors[i] = new Floor1(s);
        }

        residues = new Residue[s.read(6) + 1];
        for (int i = 0; i < residues.length; i++) residues[i] = new Residue(s);

        mappings = new Mapping[s.read(6) + 1];
        for (int i = 0; i < mappings.length; i++) mappings[i] = new Mapping(s, channels);

        modes = new Mode[s.read(6) + 1];
        for (int i = 0; i < modes.length; i++) {
            Mode mode = new Mode();
            mode.blockflag = s.read(1);
            s.read(16);
            s.read(16);
            mode.mapping = s.read(8);
            if (mode.mapping >= mappings.length) throw new IOException("Bad Vorbis mode");
            modes[i] = mode;
        }
        if (s.exhausted()) throw new IOException("The Vorbis setup header is truncated");

        for (int b = 0; b < 2; b++) {
            imdct[b] = new Imdct(blocksize[b], 1.0);
        }
        windows[0] = new float[][]{window(blocksize[0] / 2)};
        windows[1] = new float[][]{window(blocksize[0] / 2), window(blocksize[1] / 2)};

        block = new float[channels][blocksize[1]];
        previous = new float[channels][blocksize[1]];
        finalY = new int[channels][];
        for (int c = 0; c < channels; c++) {
            int most = 2;
            for (Floor1 floor : floors) most = Math.max(most, floor.values);
            finalY[c] = new int[most];
        }
        zero = new boolean[channels];
        reallyZero = new boolean[channels];
    }

    private static float[] window(int half) {
        float[] w = new float[half];
        for (int i = 0; i < half; i++) {
            double x = Math.sin((i + 0.5) / half * Math.PI / 2);
            w[i] = (float) Math.sin(Math.PI / 2 * x * x);
        }
        return w;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channels() {
        return outChannels;
    }

    @Override
    public int maxSamples() {
        return blocksize[1];
    }

    @Override
    public void reset() {
        previousN = 0;
    }

    @Override
    public int decode(Packet packet, float[][] out) throws IOException {
        in.reset(packet.data(), packet.offset(), packet.length());
        if (packet.length() == 0 || in.read(1) != 0) return 0;

        Mode mode = modes[in.read(ilog(modes.length - 1))];
        int n = blocksize[mode.blockflag];
        int n2 = n / 2;
        boolean prevLong = true;
        boolean nextLong = true;
        if (mode.blockflag == 1) {
            prevLong = in.read(1) == 1;
            nextLong = in.read(1) == 1;
        }
        Mapping map = mappings[mode.mapping];

        for (int c = 0; c < channels; c++) {
            Floor1 floor = floors[map.submapFloor[map.mux[c]]];
            zero[c] = !floor.decode(in, codebooks, finalY[c]);
        }
        System.arraycopy(zero, 0, reallyZero, 0, channels);
        for (int i = 0; i < map.couplingSteps; i++) {
            if (!zero[map.magnitude[i]] || !zero[map.angle[i]]) {
                zero[map.magnitude[i]] = false;
                zero[map.angle[i]] = false;
            }
        }

        for (int sub = 0; sub < map.submaps; sub++) {
            int count = 0;
            for (int c = 0; c < channels; c++) if (map.mux[c] == sub) count++;
            float[][] buffers = new float[count][];
            boolean[] skip = new boolean[count];
            int k = 0;
            for (int c = 0; c < channels; c++) {
                if (map.mux[c] != sub) continue;
                buffers[k] = block[c];
                skip[k] = zero[c];
                k++;
            }
            residues[map.submapResidue[sub]].decode(in, codebooks, buffers, skip, n2);
        }

        for (int i = map.couplingSteps - 1; i >= 0; i--) {
            float[] m = block[map.magnitude[i]];
            float[] a = block[map.angle[i]];
            for (int j = 0; j < n2; j++) {
                float mv = m[j];
                float av = a[j];
                float m2;
                float a2;
                if (mv > 0) {
                    if (av > 0) {
                        m2 = mv;
                        a2 = mv - av;
                    } else {
                        a2 = mv;
                        m2 = mv + av;
                    }
                } else if (av > 0) {
                    m2 = mv;
                    a2 = mv + av;
                } else {
                    a2 = mv;
                    m2 = mv - av;
                }
                m[j] = m2;
                a[j] = a2;
            }
        }

        for (int c = 0; c < channels; c++) {
            float[] buffer = block[c];
            if (reallyZero[c]) {
                Arrays.fill(buffer, 0, n2, 0);
            } else {
                floors[map.submapFloor[map.mux[c]]].apply(buffer, finalY[c], n2);
            }
        }

        int leftStart;
        int leftEnd;
        int rightStart;
        int rightEnd;
        if (mode.blockflag == 1 && !prevLong) {
            leftStart = n / 4 - blocksize[0] / 4;
            leftEnd = n / 4 + blocksize[0] / 4;
        } else {
            leftStart = 0;
            leftEnd = n2;
        }
        if (mode.blockflag == 1 && !nextLong) {
            rightStart = n * 3 / 4 - blocksize[0] / 4;
            rightEnd = n * 3 / 4 + blocksize[0] / 4;
        } else {
            rightStart = n2;
            rightEnd = n;
        }
        float[] leftWindow = leftEnd - leftStart == blocksize[0] / 2 ? windows[1][0] : windows[1][1];
        float[] rightWindow = rightEnd - rightStart == blocksize[0] / 2 ? windows[1][0] : windows[1][1];

        float[] spectrum = new float[n2];
        for (int c = 0; c < channels; c++) {
            System.arraycopy(block[c], 0, spectrum, 0, n2);
            imdct[mode.blockflag].transform(spectrum, 0, block[c], 0);
            float[] b = block[c];
            for (int i = 0; i < leftStart; i++) b[i] = 0;
            for (int i = leftStart; i < leftEnd; i++) b[i] *= leftWindow[i - leftStart];
            for (int i = rightStart; i < rightEnd; i++) b[i] *= rightWindow[rightEnd - 1 - i];
            for (int i = rightEnd; i < n; i++) b[i] = 0;
        }

        int produced = 0;
        if (previousN > 0) {
            int length = previousN / 4 + n / 4;
            int curStart = n / 4 - previousN / 4;
            produced = Math.min(length, out[0].length);
            for (int k = 0; k < produced; k++) {
                int j = curStart + k;
                int p = previousN / 2 + k;
                float left = mix(0, j, p);
                float right = channels > 1 ? mix(1, j, p) : left;
                if (channels > 2) {
                    float extra = 0;
                    for (int c = 2; c < channels; c++) extra += mix(c, j, p) * 0.7071f;
                    left = (left + extra) * 0.5f;
                    right = (right + extra) * 0.5f;
                }
                out[0][k] = left;
                if (out.length > 1) out[1][k] = outChannels == 1 ? left : right;
            }
        }
        for (int c = 0; c < channels; c++) System.arraycopy(block[c], 0, previous[c], 0, n);
        previousN = n;
        return produced;
    }

    private float mix(int c, int j, int p) {
        float v = 0;
        if (j >= 0 && j < blocksize[1]) v += block[c][j];
        if (p < previousN) v += previous[c][p];
        return v;
    }

    static int ilog(int value) {
        return value <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(value);
    }

    static float float32Unpack(int x) {
        int mantissa = x & 0x1FFFFF;
        int sign = x & 0x80000000;
        int exponent = (x & 0x7FE00000) >>> 21;
        double value = sign != 0 ? -mantissa : mantissa;
        return (float) (value * Math.pow(2, exponent - 788));
    }

    static final class Bits implements BitSource {
        private byte[] data;
        private int at;
        private int end;
        private int bit;
        private boolean exhausted;

        Bits reset(byte[] data, int offset, int length) {
            this.data = data;
            this.at = offset;
            this.end = offset + length;
            this.bit = 0;
            this.exhausted = false;
            return this;
        }

        @Override
        public int bit() {
            if (at >= end) {
                exhausted = true;
                return 0;
            }
            int value = (data[at] >> bit) & 1;
            if (++bit == 8) {
                bit = 0;
                at++;
            }
            return value;
        }

        int read(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) value |= bit() << i;
            return value;
        }

        void skipBytes(int count) {
            at += count;
        }

        boolean exhausted() {
            return exhausted;
        }
    }

    private static final class Codebook {
        final int dimensions;
        final int entries;
        final HuffmanTree tree;
        final int single;
        final float[] vectors;

        Codebook(Bits s) throws IOException {
            if (s.read(24) != 0x564342) throw new IOException("Bad Vorbis codebook sync");
            dimensions = s.read(16);
            entries = s.read(24);
            int[] lengths = new int[entries];
            if (s.read(1) == 0) {
                boolean sparse = s.read(1) == 1;
                for (int i = 0; i < entries; i++) {
                    if (!sparse || s.read(1) == 1) lengths[i] = s.read(5) + 1;
                }
            } else {
                int current = 0;
                int length = s.read(5) + 1;
                while (current < entries) {
                    int number = s.read(ilog(entries - current));
                    if (current + number > entries || length > 32) throw new IOException("Bad Vorbis codebook lengths");
                    for (int i = 0; i < number; i++) lengths[current + i] = length;
                    current += number;
                    length++;
                }
            }

            int used = 0;
            int last = -1;
            for (int i = 0; i < entries; i++) {
                if (lengths[i] > 0) {
                    used++;
                    last = i;
                }
            }
            single = used == 1 ? last : -1;
            tree = used > 1 ? new HuffmanTree(codewords(lengths), lengths) : null;

            int lookup = s.read(4);
            if (lookup == 1 || lookup == 2) {
                float min = float32Unpack(s.read(32));
                float delta = float32Unpack(s.read(32));
                int valueBits = s.read(4) + 1;
                boolean sequence = s.read(1) == 1;
                int values = lookup == 1 ? lookup1(entries, dimensions) : entries * dimensions;
                int[] multiplicands = new int[values];
                for (int i = 0; i < values; i++) multiplicands[i] = s.read(valueBits);
                vectors = new float[entries * dimensions];
                for (int e = 0; e < entries; e++) {
                    float lastValue = 0;
                    int divisor = 1;
                    for (int d = 0; d < dimensions; d++) {
                        int offset = lookup == 1 ? (e / divisor) % values : e * dimensions + d;
                        float v = multiplicands[offset] * delta + min + lastValue;
                        vectors[e * dimensions + d] = v;
                        if (sequence) lastValue = v;
                        if (lookup == 1) divisor *= values;
                    }
                }
            } else if (lookup == 0) {
                vectors = null;
            } else {
                throw new IOException("Bad Vorbis codebook lookup type " + lookup);
            }
        }

        private static int lookup1(int entries, int dimensions) {
            int r = (int) Math.floor(Math.exp(Math.log(entries) / dimensions));
            while (Math.pow(r + 1, dimensions) <= entries) r++;
            while (r > 0 && Math.pow(r, dimensions) > entries) r--;
            return r;
        }

        private static int[] codewords(int[] lengths) throws IOException {
            int[] codes = new int[lengths.length];
            long[] available = new long[33];
            int k = 0;
            while (k < lengths.length && lengths[k] == 0) k++;
            if (k == lengths.length) return codes;
            codes[k] = 0;
            for (int i = 1; i <= lengths[k]; i++) available[i] = 1L << (32 - i);
            for (int i = k + 1; i < lengths.length; i++) {
                int z = lengths[i];
                if (z == 0) continue;
                while (z > 0 && available[z] == 0) z--;
                if (z == 0) throw new IOException("A Vorbis codebook is overspecified");
                long res = available[z];
                available[z] = 0;
                codes[i] = (int) (res >>> (32 - lengths[i]));
                for (int y = lengths[i]; y > z; y--) available[y] = res + (1L << (32 - y));
            }
            return codes;
        }

        int decode(Bits in) {
            if (single >= 0) {
                in.bit();
                return in.exhausted() ? -1 : single;
            }
            if (tree == null) return -1;
            int entry = tree.decode(in);
            return in.exhausted() ? -1 : entry;
        }
    }

    private static final class Floor1 {
        final int partitions;
        final int[] partitionClass;
        final int[] classDimensions = new int[16];
        final int[] classSubclasses = new int[16];
        final int[] classMasterbooks = new int[16];
        final int[][] subclassBooks = new int[16][8];
        final int multiplier;
        final int[] x;
        final int values;
        final int[] sorted;
        final int[] low;
        final int[] high;

        Floor1(Bits s) throws IOException {
            partitions = s.read(5);
            partitionClass = new int[partitions];
            int maxClass = -1;
            for (int i = 0; i < partitions; i++) {
                partitionClass[i] = s.read(4);
                maxClass = Math.max(maxClass, partitionClass[i]);
            }
            for (int c = 0; c <= maxClass; c++) {
                classDimensions[c] = s.read(3) + 1;
                classSubclasses[c] = s.read(2);
                if (classSubclasses[c] != 0) classMasterbooks[c] = s.read(8);
                for (int j = 0; j < (1 << classSubclasses[c]); j++) subclassBooks[c][j] = s.read(8) - 1;
            }
            multiplier = s.read(2) + 1;
            int rangeBits = s.read(4);
            int[] list = new int[2 + 16 * 8 * 32];
            int count = 2;
            list[0] = 0;
            list[1] = 1 << rangeBits;
            for (int i = 0; i < partitions; i++) {
                int dims = classDimensions[partitionClass[i]];
                for (int j = 0; j < dims; j++) list[count++] = s.read(rangeBits);
            }
            x = Arrays.copyOf(list, count);
            values = count;
            if (values > 65) throw new IOException("A Vorbis floor lists too many points");

            Integer[] order = new Integer[values];
            for (int i = 0; i < values; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Integer.compare(x[a], x[b]));
            sorted = new int[values];
            for (int i = 0; i < values; i++) sorted[i] = order[i];

            low = new int[values];
            high = new int[values];
            for (int i = 2; i < values; i++) {
                int lo = 0;
                int hi = 1;
                int loX = -1;
                int hiX = Integer.MAX_VALUE;
                for (int j = 0; j < i; j++) {
                    if (x[j] > loX && x[j] < x[i]) {
                        loX = x[j];
                        lo = j;
                    }
                    if (x[j] < hiX && x[j] > x[i]) {
                        hiX = x[j];
                        hi = j;
                    }
                }
                low[i] = lo;
                high[i] = hi;
            }
        }

        boolean decode(Bits in, Codebook[] books, int[] y) {
            if (in.read(1) == 0) return false;
            int range = FLOOR1_RANGE[multiplier - 1];
            int bits = ilog(range - 1);
            y[0] = in.read(bits);
            y[1] = in.read(bits);
            int offset = 2;
            for (int i = 0; i < partitions; i++) {
                int c = partitionClass[i];
                int dims = classDimensions[c];
                int cbits = classSubclasses[c];
                int csub = (1 << cbits) - 1;
                int cval = 0;
                if (cbits > 0) {
                    cval = books[classMasterbooks[c]].decode(in);
                    if (cval < 0) return false;
                }
                for (int k = 0; k < dims; k++) {
                    int book = subclassBooks[c][cval & csub];
                    cval >>= cbits;
                    if (book >= 0) {
                        int v = books[book].decode(in);
                        if (v < 0) return false;
                        y[offset++] = v;
                    } else {
                        y[offset++] = 0;
                    }
                }
            }
            if (in.exhausted()) return false;

            boolean[] step2 = new boolean[values];
            step2[0] = true;
            step2[1] = true;
            for (int i = 2; i < values; i++) {
                int lo = low[i];
                int hi = high[i];
                int predicted = predict(x[i], x[lo], x[hi], y[lo], y[hi]);
                int val = y[i];
                int highroom = range - predicted;
                int lowroom = predicted;
                int room = (highroom < lowroom ? highroom : lowroom) * 2;
                if (val != 0) {
                    step2[lo] = true;
                    step2[hi] = true;
                    step2[i] = true;
                    if (val >= room) {
                        y[i] = highroom > lowroom ? val - lowroom + predicted : predicted - val + highroom - 1;
                    } else {
                        y[i] = (val & 1) != 0 ? predicted - ((val + 1) >> 1) : predicted + (val >> 1);
                    }
                } else {
                    y[i] = predicted;
                }
            }
            for (int i = 0; i < values; i++) if (!step2[i]) y[i] = -1;
            return true;
        }

        void apply(float[] target, int[] y, int n2) {
            int lx = 0;
            int ly = y[0] * multiplier;
            for (int q = 1; q < values; q++) {
                int j = sorted[q];
                if (y[j] < 0) continue;
                int hy = y[j] * multiplier;
                int hx = x[j];
                if (lx != hx) line(target, lx, ly, hx, hy, n2);
                lx = hx;
                ly = hy;
            }
            for (int j = lx; j < n2; j++) target[j] *= INVERSE_DB[ly & 255];
        }

        private static int predict(int px, int x0, int x1, int y0, int y1) {
            int dy = y1 - y0;
            int adx = x1 - x0;
            int err = Math.abs(dy) * (px - x0);
            int off = err / adx;
            return dy < 0 ? y0 - off : y0 + off;
        }

        private static void line(float[] out, int x0, int y0, int x1, int y1, int n) {
            int dy = y1 - y0;
            int adx = x1 - x0;
            int ady = Math.abs(dy);
            int base = dy / adx;
            int sy = dy < 0 ? base - 1 : base + 1;
            int x = x0;
            int y = y0;
            int err = 0;
            ady -= Math.abs(base) * adx;
            if (x1 > n) x1 = n;
            if (x < x1) {
                out[x] *= INVERSE_DB[y & 255];
                for (++x; x < x1; ++x) {
                    err += ady;
                    if (err >= adx) {
                        err -= adx;
                        y += sy;
                    } else {
                        y += base;
                    }
                    out[x] *= INVERSE_DB[y & 255];
                }
            }
        }
    }

    private static final class Residue {
        final int type;
        final int begin;
        final int end;
        final int partitionSize;
        final int classifications;
        final int classbook;
        final int[][] books;

        Residue(Bits s) throws IOException {
            type = s.read(16);
            if (type > 2) throw new IOException("Bad Vorbis residue type " + type);
            begin = s.read(24);
            end = s.read(24);
            partitionSize = s.read(24) + 1;
            classifications = s.read(6) + 1;
            classbook = s.read(8);
            int[] cascade = new int[classifications];
            for (int i = 0; i < classifications; i++) {
                int lowBits = s.read(3);
                int highBits = s.read(1) == 1 ? s.read(5) : 0;
                cascade[i] = highBits * 8 + lowBits;
            }
            books = new int[classifications][8];
            for (int i = 0; i < classifications; i++) {
                for (int j = 0; j < 8; j++) books[i][j] = (cascade[i] & (1 << j)) != 0 ? s.read(8) : -1;
            }
        }

        void decode(Bits in, Codebook[] codebooks, float[][] buffers, boolean[] skip, int n2) {
            int ch = buffers.length;
            for (int c = 0; c < ch; c++) if (!skip[c]) Arrays.fill(buffers[c], 0, n2, 0);

            if (type == 2 && ch > 1) {
                boolean any = false;
                for (boolean s : skip) any |= !s;
                if (!any) return;
                float[] interleaved = new float[n2 * ch];
                decodePartitions(in, codebooks, new float[][]{interleaved}, new boolean[]{false}, n2 * ch, 1);
                for (int c = 0; c < ch; c++) {
                    if (skip[c]) continue;
                    float[] target = buffers[c];
                    for (int j = 0; j < n2; j++) target[j] = interleaved[j * ch + c];
                }
                return;
            }
            decodePartitions(in, codebooks, buffers, skip, n2, type == 0 ? 0 : 1);
        }

        private void decodePartitions(Bits in, Codebook[] codebooks, float[][] buffers, boolean[] skip,
                                      int size, int format) {
            int ch = buffers.length;
            int limitBegin = Math.min(begin, size);
            int limitEnd = Math.min(end, size);
            int partitions = (limitEnd - limitBegin) / partitionSize;
            if (partitions <= 0) return;
            Codebook classBook = codebooks[classbook];
            int perWord = classBook.dimensions;
            int[][] classes = new int[ch][partitions + perWord];

            for (int pass = 0; pass < 8; pass++) {
                int count = 0;
                while (count < partitions) {
                    if (pass == 0) {
                        for (int c = 0; c < ch; c++) {
                            if (skip[c]) continue;
                            int temp = classBook.decode(in);
                            if (temp < 0) return;
                            for (int i = perWord - 1; i >= 0; i--) {
                                classes[c][i + count] = temp % classifications;
                                temp /= classifications;
                            }
                        }
                    }
                    for (int i = 0; i < perWord && count < partitions; i++, count++) {
                        for (int c = 0; c < ch; c++) {
                            if (skip[c]) continue;
                            int book = books[classes[c][count]][pass];
                            if (book < 0) continue;
                            Codebook vq = codebooks[book];
                            if (vq.vectors == null) continue;
                            int offset = limitBegin + count * partitionSize;
                            if (!decodeVector(in, vq, buffers[c], offset, format)) return;
                        }
                    }
                }
            }
        }

        private boolean decodeVector(Bits in, Codebook book, float[] target, int offset, int format) {
            int dim = book.dimensions;
            if (format == 0) {
                int step = partitionSize / dim;
                for (int j = 0; j < step; j++) {
                    int entry = book.decode(in);
                    if (entry < 0) return false;
                    for (int k = 0; k < dim; k++) target[offset + j + k * step] += book.vectors[entry * dim + k];
                }
            } else {
                int i = 0;
                while (i < partitionSize) {
                    int entry = book.decode(in);
                    if (entry < 0) return false;
                    for (int k = 0; k < dim && i < partitionSize; k++) target[offset + i++] += book.vectors[entry * dim + k];
                }
            }
            return true;
        }
    }

    private static final class Mapping {
        final int submaps;
        final int couplingSteps;
        final int[] magnitude;
        final int[] angle;
        final int[] mux;
        final int[] submapFloor;
        final int[] submapResidue;

        Mapping(Bits s, int channels) throws IOException {
            if (s.read(16) != 0) throw new IOException("Bad Vorbis mapping type");
            submaps = s.read(1) == 1 ? s.read(4) + 1 : 1;
            if (s.read(1) == 1) {
                couplingSteps = s.read(8) + 1;
                magnitude = new int[couplingSteps];
                angle = new int[couplingSteps];
                int bits = ilog(channels - 1);
                for (int i = 0; i < couplingSteps; i++) {
                    magnitude[i] = s.read(bits);
                    angle[i] = s.read(bits);
                    if (magnitude[i] >= channels || angle[i] >= channels || magnitude[i] == angle[i]) {
                        throw new IOException("Bad Vorbis channel coupling");
                    }
                }
            } else {
                couplingSteps = 0;
                magnitude = new int[0];
                angle = new int[0];
            }
            if (s.read(2) != 0) throw new IOException("Bad Vorbis mapping reserved bits");
            mux = new int[channels];
            if (submaps > 1) {
                for (int c = 0; c < channels; c++) {
                    mux[c] = s.read(4);
                    if (mux[c] >= submaps) throw new IOException("Bad Vorbis channel mux");
                }
            }
            submapFloor = new int[submaps];
            submapResidue = new int[submaps];
            for (int i = 0; i < submaps; i++) {
                s.read(8);
                submapFloor[i] = s.read(8);
                submapResidue[i] = s.read(8);
            }
        }
    }

    private static final class Mode {
        int blockflag;
        int mapping;
    }
}
