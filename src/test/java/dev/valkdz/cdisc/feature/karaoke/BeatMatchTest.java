package dev.valkdz.cdisc.feature.karaoke;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeatMatchTest {

    private static final int RATE = BeatMatch.RATE;

    @Test
    void findsTheSameBeatAndHowFarItIsShifted() {
        float[] beat = beat(7, 120, 1.0);
        float[] original = withVoice(shift(beat, 3.37), 11);
        float[] minus = slice(beat, 0, 100);

        BeatMatch.Result result = BeatMatch.compare(original, minus);

        assertTrue(result.aligned(), result.toString());
        assertTrue(result.sameSong(), result.toString());
        assertEquals(3370, result.offsetMs(), 20);
    }

    @Test
    void anotherBeatIsNotTheSameSong() {
        BeatMatch.Result result = BeatMatch.compare(beat(7, 120, 1.0), slice(beat(8, 120, 128.0 / 140), 0, 100));
        assertFalse(result.aligned(), result.toString());
        assertFalse(result.sameSong(), result.toString());
    }

    @Test
    void findsHowMuchFasterAReuploadPlays() {
        float[] original = withVoice(shift(beat(7, 120, 1.0), 3.37), 11);
        float[] faster = slice(beat(7, 120, 1.015), 0, 100);

        BeatMatch.Result result = BeatMatch.compare(original, faster);

        assertTrue(result.aligned(), result.toString());
        assertEquals(1.015, result.speed(), 0.001);
        assertEquals(3370, result.offsetMs(), 40);
        assertEquals(53_370, result.toOriginal(49_261), 60);
        assertEquals(49_261, result.toCandidate(53_370), 60);
    }

    @Test
    void aVersionAtItsOwnSpeedIsNotStretched() {
        float[] beat = beat(7, 120, 1.0);
        assertEquals(1.0, BeatMatch.compare(withVoice(beat, 11), slice(beat, 10, 90)).speed());
    }

    @Test
    void aSpedUpVersionIsNotTheSameSong() {
        float[] beat = beat(7, 120, 1.0);
        BeatMatch.Result result = BeatMatch.compare(beat, slice(beat(7, 120, 1.06), 0, 100));
        assertFalse(result.aligned(), result.toString());
    }

    // A song, not a loop: verse, chorus and bridge patterns in an uneven order, with breaks,
    // so only the true shift lines the two up everywhere.
    private static float[] beat(long seed, int seconds, double speed) {
        Random random = new Random(seed);
        float[] out = new float[seconds * RATE];
        double step = 60.0 / 140 / 2 / speed;
        boolean[][] patterns = new boolean[3][32];
        for (boolean[] pattern : patterns) {
            for (int i = 0; i < pattern.length; i++) pattern[i] = i % 8 == 0 || random.nextDouble() < 0.3;
        }
        int[] form = {0, 0, 1, 0, 2, 1, 1, 0, 2, 2, 1, 0, 1, 2, 0, 1};
        for (int k = 0; k * step < seconds; k++) {
            int bar = k / 32;
            if (bar % 5 == 3 && k % 32 < 8) continue;
            boolean[] pattern = patterns[form[bar % form.length]];
            if (!pattern[k % 32]) continue;
            int at = (int) (k * step * RATE);
            for (int i = 0; i < RATE / 8 && at + i < out.length; i++) {
                double t = i / (double) RATE;
                out[at + i] += (float) (0.6 * Math.exp(-t * 18) * Math.sin(2 * Math.PI * 55 * t));
            }
        }
        return out;
    }

    private static float[] withVoice(float[] music, long seed) {
        Random random = new Random(seed);
        float[] out = music.clone();
        for (int i = 0; i < out.length; i++) out[i] += (float) (0.2 * random.nextGaussian() * Math.sin(2 * Math.PI * 900 * i / RATE));
        return out;
    }

    private static float[] shift(float[] x, double seconds) {
        int by = (int) (seconds * RATE);
        float[] out = new float[x.length + by];
        System.arraycopy(x, 0, out, by, x.length);
        return out;
    }

    private static float[] slice(float[] x, int fromSeconds, int seconds) {
        float[] out = new float[seconds * RATE];
        System.arraycopy(x, fromSeconds * RATE, out, 0, out.length);
        return out;
    }
}
