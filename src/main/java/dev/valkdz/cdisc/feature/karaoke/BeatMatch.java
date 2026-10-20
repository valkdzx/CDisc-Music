package dev.valkdz.cdisc.feature.karaoke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BeatMatch {

    public static final int RATE = 8_000;
    private static final int HOP = RATE / 100;
    private static final int MAX_LAG = 60 * 100;
    private static final int WINDOW = 20 * 100;
    private static final int AGREE = 10;
    private static final int APART = 50;
    private static final double MIN_SCORE = 0.3;
    // Choruses repeat, so a shift by a few bars scores close to the true one; the windows
    // agreeing on one shift is what proves it, not a wide margin.
    private static final double MIN_MARGIN = 1.2;
    private static final double MIN_AGREEMENT = 0.6;
    private static final double MIN_SIMILARITY = 0.3;
    private static final int MAX_DRIFT_PERMILLE = 30;
    private static final double SAME_SPEED = 0.97;

    private BeatMatch() {
    }

    // offsetMs and speed: the candidate at t plays what the original plays at t * speed + offsetMs.
    // aligned: one shift holds everywhere; sameSong: the same beat, though its sections may be cut differently.
    public record Result(boolean aligned, boolean sameSong, long offsetMs, double speed, double score,
                         double margin, double agreement, double similarity) {

        public long toCandidate(long originalMs) {
            return Math.round((originalMs - offsetMs) / speed);
        }

        public long toOriginal(long candidateMs) {
            return Math.round(candidateMs * speed) + offsetMs;
        }
    }

    public static Result compare(float[] original, float[] candidate) {
        float[] a = onsets(original);
        float[] raw = onsets(candidate);
        if (a.length < WINDOW || raw.length < WINDOW) return new Result(false, false, 0, 1, 0, 0, 0, 0);

        double speed = speedOf(a, raw);
        float[] b = speed == 1 ? raw : stretch(raw, speed);
        Fft.Spectrum spectrum = Fft.Spectrum.of(a, Fft.size(a.length + b.length + 1));
        Peak global = peak(Fft.correlate(spectrum, b, MAX_LAG), b.length);
        List<Double> windowScores = new ArrayList<>();
        List<Integer> windowLags = new ArrayList<>();
        int agreeing = 0;
        for (int from = 0; from + WINDOW <= b.length; from += WINDOW) {
            double energy = 0;
            for (int i = from; i < from + WINDOW; i++) energy += b[i] * b[i];
            if (energy / WINDOW < 0.2) continue;
            Peak window = best(a, b, from, from + WINDOW);
            windowScores.add(window.score);
            windowLags.add(window.lag);
            if (Math.abs(window.lag - global.lag) <= AGREE) agreeing++;
        }

        int counted = windowScores.size();
        double margin = global.second <= 0 ? Double.MAX_VALUE : global.score / global.second;
        double agreement = counted == 0 ? 0 : agreeing / (double) counted;
        double similarity = counted == 0 ? 0 : median(windowScores);
        boolean aligned = global.score >= MIN_SCORE && margin >= MIN_MARGIN && agreement >= MIN_AGREEMENT;
        // Two songs at one tempo line their kicks up at some shift in every window, but only the
        // same song, recut, has two windows landing on the same shift.
        boolean sameSong = aligned || (similarity >= MIN_SIMILARITY && twoAgree(windowLags));
        return new Result(aligned, sameSong, global.lag * 10L, speed, global.score, margin, agreement, similarity);
    }

    // Re-uploads are often a percent or two fast; off by that much, the onsets drift apart within
    // seconds, so the speed is found first: coarse over the whole range, then finely around the best.
    static double speedOf(float[] a, float[] b) {
        float[] coarseA = coarse(a);
        Fft.Spectrum coarse = Fft.Spectrum.of(coarseA, Fft.size(coarseA.length + b.length / 2 + b.length / 50 + 1));
        double bestSpeed = 1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int permille = -MAX_DRIFT_PERMILLE; permille <= MAX_DRIFT_PERMILLE; permille++) {
            double speed = 1 + permille / 1000.0;
            float[] stretched = coarse(stretch(b, speed));
            double score = peak(Fft.correlate(coarse, stretched, MAX_LAG / 2), stretched.length).score;
            if (score > bestScore) {
                bestScore = score;
                bestSpeed = speed;
            }
        }

        Fft.Spectrum fine = Fft.Spectrum.of(a, Fft.size(a.length + b.length + b.length / 25 + 1));
        double atOne = peak(Fft.correlate(fine, b, MAX_LAG), b.length).score;
        double refined = 1;
        double refinedScore = atOne;
        for (int step = -5; step <= 5; step++) {
            double speed = bestSpeed + step * 0.0002;
            if (Math.abs(speed - 1) < 1e-9 || Math.abs(speed - 1) > MAX_DRIFT_PERMILLE / 1000.0 + 1e-9) continue;
            float[] stretched = stretch(b, speed);
            double score = peak(Fft.correlate(fine, stretched, MAX_LAG), stretched.length).score;
            if (score > refinedScore) {
                refinedScore = score;
                refined = speed;
            }
        }
        return atOne >= refinedScore * SAME_SPEED ? 1 : refined;
    }

    // The candidate's envelope on the original's clock: sample k is the candidate at k / speed.
    static float[] stretch(float[] x, double speed) {
        float[] out = new float[(int) (x.length * speed)];
        for (int k = 0; k < out.length; k++) {
            double at = k / speed;
            int i = (int) at;
            if (i + 1 >= x.length) {
                out[k] = x[x.length - 1];
                continue;
            }
            double t = at - i;
            out[k] = (float) (x[i] * (1 - t) + x[i + 1] * t);
        }
        return out;
    }

    private static float[] coarse(float[] x) {
        float[] half = new float[x.length / 2];
        for (int i = 0; i < half.length; i++) half[i] = x[2 * i] + x[2 * i + 1];
        float[] out = new float[half.length];
        for (int i = 0; i < half.length; i++) {
            float sum = half[i];
            if (i > 0) sum += half[i - 1];
            if (i + 1 < half.length) sum += half[i + 1];
            out[i] = sum;
        }
        return out;
    }

    private static boolean twoAgree(List<Integer> lags) {
        for (int i = 0; i < lags.size(); i++) {
            for (int j = i + 1; j < lags.size(); j++) {
                if (Math.abs(lags.get(i) - lags.get(j)) <= AGREE) return true;
            }
        }
        return false;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    static float[] onsets(float[] pcm) {
        Biquad first = Biquad.lowPass(400, RATE);
        Biquad second = Biquad.lowPass(400, RATE);
        int frames = pcm.length / HOP;
        float[] out = new float[frames];
        double previous = 0;
        for (int f = 0; f < frames; f++) {
            double energy = 0;
            for (int i = f * HOP; i < (f + 1) * HOP; i++) {
                double low = second.apply(first.apply(pcm[i]));
                energy += low * low;
            }
            double level = Math.log1p(100 * energy / HOP);
            out[f] = (float) Math.max(0, level - previous);
            previous = level;
        }

        double mean = 0;
        for (float v : out) mean += v;
        mean /= Math.max(1, frames);
        double variance = 0;
        for (float v : out) variance += (v - mean) * (v - mean);
        double deviation = Math.sqrt(variance / Math.max(1, frames)) + 1e-9;
        for (int f = 0; f < frames; f++) out[f] = (float) ((out[f] - mean) / deviation);
        return out;
    }

    private record Peak(int lag, double score, double second) {
    }

    private static Peak peak(double[] sums, int length) {
        int maxLag = (sums.length - 1) / 2;
        int bestIndex = 0;
        for (int i = 1; i < sums.length; i++) {
            if (sums[i] > sums[bestIndex]) bestIndex = i;
        }
        double second = 0;
        for (int i = 0; i < sums.length; i++) {
            if (Math.abs(i - bestIndex) > APART) second = Math.max(second, sums[i]);
        }
        return new Peak(bestIndex - maxLag, sums[bestIndex] / length, second / length);
    }

    private static Peak best(float[] a, float[] b, int from, int to) {
        double[] scores = new double[2 * MAX_LAG + 1];
        int bestIndex = 0;
        for (int lag = -MAX_LAG; lag <= MAX_LAG; lag++) {
            int start = Math.max(from, -lag);
            int end = Math.min(to, a.length - lag);
            double sum = 0;
            for (int i = start; i < end; i++) sum += a[i + lag] * b[i];
            scores[lag + MAX_LAG] = sum / (to - from);
            if (scores[lag + MAX_LAG] > scores[bestIndex]) bestIndex = lag + MAX_LAG;
        }
        double second = 0;
        for (int i = 0; i < scores.length; i++) {
            if (Math.abs(i - bestIndex) > APART) second = Math.max(second, scores[i]);
        }
        return new Peak(bestIndex - MAX_LAG, scores[bestIndex], second);
    }
}
