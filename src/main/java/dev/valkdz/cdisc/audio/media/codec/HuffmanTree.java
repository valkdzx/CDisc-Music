package dev.valkdz.cdisc.audio.media.codec;

final class HuffmanTree {

    private int[] left = new int[64];
    private int[] right = new int[64];
    private int count = 1;

    HuffmanTree(int[] codes, int[] bits) {
        for (int i = 0; i < codes.length; i++) {
            if (bits[i] > 0) add(codes[i], bits[i], i);
        }
    }

    private void add(int code, int length, int symbol) {
        int node = 0;
        for (int b = length - 1; b >= 0; b--) {
            boolean one = ((code >>> b) & 1) != 0;
            if (b == 0) {
                (one ? right : left)[node] = -1 - symbol;
                return;
            }
            if ((one ? right : left)[node] <= 0) {
                if (count == left.length) {
                    left = java.util.Arrays.copyOf(left, count * 2);
                    right = java.util.Arrays.copyOf(right, count * 2);
                }
                (one ? right : left)[node] = count++;
            }
            node = (one ? right : left)[node];
        }
    }

    int decode(BitReader in) {
        int node = 0;
        for (int depth = 0; depth < 32; depth++) {
            int next = in.bit() != 0 ? right[node] : left[node];
            if (next < 0) return -1 - next;
            if (next == 0) return -1;
            node = next;
        }
        return -1;
    }
}
