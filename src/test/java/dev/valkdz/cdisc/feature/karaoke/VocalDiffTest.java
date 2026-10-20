package dev.valkdz.cdisc.feature.karaoke;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VocalDiffTest {

    private static final int RATE = BeatMatch.RATE;

    @Test
    void findsTheWordTheCleanCutMutes() {
        List<VocalDiff.Spot> spots = VocalDiff.spots(song(40, -1, 0, false), song(40, 20.0, 0.4, false), 0, 1);

        assertEquals(1, spots.size(), spots.toString());
        VocalDiff.Spot spot = spots.get(0);
        assertEquals(20_000, spot.atMs(), 100);
        assertTrue(spot.lengthMs() >= 400 && spot.lengthMs() <= 700, spot.toString());
        assertTrue(spot.levelDb() > 10, spot.toString());
    }

    @Test
    void findsAWordSungDifferentlyAtTheSameLevel() {
        List<VocalDiff.Spot> spots = VocalDiff.spots(song(40, 20.0, 0.5, true), song(40, -1, 0, false), 0, 1);

        assertEquals(1, spots.size(), spots.toString());
        assertEquals(20_000, spots.get(0).atMs(), 100);
        assertTrue(Math.abs(spots.get(0).levelDb()) < 4, spots.toString());
    }

    @Test
    void theSameCutHasNothingToReport() {
        assertTrue(VocalDiff.spots(song(40, -1, 0, false), song(40, -1, 0, false), 0, 1).isEmpty());
    }

    @Test
    void reportsOnTheFirstClockForAShiftedFasterCopy() {
        float[] censored = song(45, 11.5, 0.3, false);
        double speed = 1.01;
        float[] fast = new float[(int) ((censored.length - 1.5 * RATE) / speed)];
        for (int k = 0; k < fast.length; k++) fast[k] = censored[(int) (k * speed + 1.5 * RATE)];

        List<VocalDiff.Spot> spots = VocalDiff.spots(song(45, -1, 0, false), fast, 1500, speed);

        assertEquals(1, spots.size(), spots.toString());
        assertEquals(11_500, spots.get(0).atMs(), 100);
        assertTrue(spots.get(0).levelDb() > 10, spots.toString());
    }

    // A kick every half second under a voice that speaks in syllables; from changeAt, for changeFor
    // seconds, the voice is gone, or sings other notes when replaced.
    private static float[] song(int seconds, double changeAt, double changeFor, boolean replaced) {
        float[] out = new float[seconds * RATE];
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            double kickT = t % 0.5;
            double kick = 0.5 * Math.exp(-kickT * 20) * Math.sin(2 * Math.PI * 55 * kickT);
            double syllable = 0.3 + 0.7 * Math.abs(Math.sin(Math.PI * 4 * t));
            boolean changed = t >= changeAt && t < changeAt + changeFor;
            double f = changed && replaced ? 870 : 600;
            double voice = 0.15 * syllable * (Math.sin(2 * Math.PI * f * t) + Math.sin(2 * Math.PI * 2 * f * t)
                    + 0.5 * Math.sin(2 * Math.PI * 3 * f * t));
            if (changed && !replaced) voice = 0;
            out[i] = (float) (kick + voice);
        }
        return out;
    }
}
