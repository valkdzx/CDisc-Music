package dev.valkdz.cdisc.audio.media.codec;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImdctTest {

    @Test
    void matchesTheDefinition() {
        for (int n : new int[]{16, 256, 2048}) {
            Random random = new Random(n);
            float[] in = new float[n / 2];
            for (int i = 0; i < in.length; i++) in[i] = random.nextFloat() * 2 - 1;
            float[] out = new float[n];
            new Imdct(n, 1.0).transform(in, 0, out, 0);
            for (int i = 0; i < n; i++) {
                double expected = 0;
                for (int k = 0; k < n / 2; k++) {
                    expected += in[k] * Math.cos(2 * Math.PI / n * (i + n / 4.0 + 0.5) * (k + 0.5));
                }
                assertEquals(expected, out[i], 1e-3 * Math.sqrt(n), "n=" + n + " i=" + i);
            }
        }
    }
}
