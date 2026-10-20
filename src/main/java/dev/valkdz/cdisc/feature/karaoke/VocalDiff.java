package dev.valkdz.cdisc.feature.karaoke;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VocalDiff {

    private static final int RATE = BeatMatch.RATE;
    private static final int SIZE = 512;
    private static final int HOP = RATE / 50;
    private static final int LOW_BIN = (int) Math.ceil(300.0 * SIZE / RATE);
    private static final int HIGH_BIN = (int) Math.floor(3400.0 * SIZE / RATE);
    private static final double LOUD = 0.35;
    private static final double SPREAD = 6;
    private static final int MIN_FRAMES = 8;

    private VocalDiff() {
    }

    // On the first version's clock. levelDb: how much louder the first sings there, the overall
    // gain taken out; far below zero means the first lost the word, near zero that it was replaced.
    public record Spot(long atMs, long lengthMs, double strength, double levelDb) {
    }

    public static final class Reference {
        private final Spectrum spectrum;

        private Reference(Spectrum spectrum) {
            this.spectrum = spectrum;
        }
    }

    public static Reference reference(float[] first) {
        return new Reference(spectrum(first));
    }

    public static List<Spot> spots(float[] first, float[] second, long offsetMs, double speed) {
        return spots(reference(first), second, offsetMs, speed);
    }

    // offsetMs and speed as BeatMatch reports them for the second against the first.
    public static List<Spot> spots(Reference first, float[] second, long offsetMs, double speed) {
        Spectrum a = first.spectrum;
        Spectrum b = spectrum(speed == 1 ? second : BeatMatch.stretch(second, speed));
        int shift = (int) Math.round(offsetMs / 20.0);
        int from = Math.max(0, -shift);
        int to = Math.min(b.frames(), a.frames() - shift);
        int n = to - from;
        if (n < MIN_FRAMES * 4) return List.of();

        int bins = HIGH_BIN - LOW_BIN + 1;
        double[] medianA = new double[bins];
        double[] medianB = new double[bins];
        double[] column = new double[n];
        for (int k = 0; k < bins; k++) {
            for (int i = 0; i < n; i++) column[i] = a.log[from + i + shift][k];
            medianA[k] = percentile(column, 0.5);
            for (int i = 0; i < n; i++) column[i] = b.log[from + i][k];
            medianB[k] = percentile(column, 0.5);
        }

        double[] apart = new double[n];
        double[] levelA = new double[n];
        double[] levelB = new double[n];
        for (int i = 0; i < n; i++) {
            float[] x = a.log[from + i + shift];
            float[] y = b.log[from + i];
            double sum = 0;
            for (int k = 0; k < bins; k++) sum += Math.abs((x[k] - medianA[k]) - (y[k] - medianB[k]));
            apart[i] = sum / bins;
            levelA[i] = a.level[from + i + shift];
            levelB[i] = b.level[from + i];
        }
        double[] smooth = average5(apart);
        double quietA = percentile(levelA, LOUD);
        double quietB = percentile(levelB, LOUD);
        boolean[] loud = new boolean[n];
        List<Double> heard = new ArrayList<>();
        List<Double> gains = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            loud[i] = levelA[i] > quietA || levelB[i] > quietB;
            if (!loud[i]) continue;
            heard.add(smooth[i]);
            gains.add(levelA[i] - levelB[i]);
        }
        if (heard.isEmpty()) return List.of();
        double base = median(heard);
        List<Double> deviations = new ArrayList<>(heard.size());
        for (double v : heard) deviations.add(Math.abs(v - base));
        double threshold = base + SPREAD * median(deviations);
        double gain = median(gains);

        List<Spot> spots = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (smooth[i] <= threshold || !loud[i]) {
                i++;
                continue;
            }
            int end = i;
            double strength = 0;
            double level = 0;
            while (end < n && smooth[end] > threshold) {
                strength = Math.max(strength, smooth[end]);
                level += levelA[end] - levelB[end];
                end++;
            }
            if (end - i >= MIN_FRAMES) {
                spots.add(new Spot((from + i + shift) * 20L, (end - i) * 20L, strength, level / (end - i) - gain));
            }
            i = end;
        }
        return spots;
    }

    private record Spectrum(float[][] log, float[] level) {

        int frames() {
            return level.length;
        }
    }

    private static Spectrum spectrum(float[] pcm) {
        int frames = pcm.length / HOP + 1;
        int bins = HIGH_BIN - LOW_BIN + 1;
        float[][] log = new float[frames][bins];
        float[] level = new float[frames];
        double[] window = new double[SIZE];
        for (int i = 0; i < SIZE; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (SIZE - 1));
        double[] re = new double[SIZE];
        double[] im = new double[SIZE];
        for (int f = 0; f < frames; f++) {
            int start = f * HOP - SIZE / 2;
            for (int i = 0; i < SIZE; i++) {
                int at = start + i;
                re[i] = at >= 0 && at < pcm.length ? pcm[at] * window[i] : 0;
                im[i] = 0;
            }
            Fft.transform(re, im, false);
            double band = 0;
            for (int k = 0; k < bins; k++) {
                double magnitude = Math.hypot(re[LOW_BIN + k], im[LOW_BIN + k]);
                log[f][k] = (float) Math.log10(magnitude + 1e-6);
                band += magnitude;
            }
            level[f] = (float) (20 * Math.log10(band + 1e-9));
        }
        return new Spectrum(log, level);
    }

    private static double[] average5(double[] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double sum = 0;
            for (int k = -2; k <= 2; k++) {
                int j = i + k;
                if (j >= 0 && j < x.length) sum += x[j];
            }
            out[i] = sum / 5;
        }
        return out;
    }

    private static double median(List<Double> values) {
        double[] x = new double[values.size()];
        for (int i = 0; i < x.length; i++) x[i] = values.get(i);
        return percentile(x, 0.5);
    }

    private static double percentile(double[] x, double p) {
        double[] sorted = x.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.min(sorted.length - 1, Math.round(p * (sorted.length - 1)))];
    }
}
