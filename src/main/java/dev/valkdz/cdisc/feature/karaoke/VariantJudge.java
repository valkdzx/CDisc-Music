package dev.valkdz.cdisc.feature.karaoke;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class VariantJudge {

    private static final long WORD_MS = 3_000;
    private static final int MAX_SPOTS = 30;
    private static final double MAX_SHARE = 0.1;
    private static final double MUTED_DB = -4;
    private static final double SILENT_DB = 30;
    private static final long NEAR_MS = 500;
    private static final int TAKE_SPOTS = 5;
    private static final long TAKE_MS = 8_000;

    private VariantJudge() {
    }

    // spots: where the candidate sings differently from the original, on the original's clock.
    public record Heard(VariantFinder.Hit hit, BeatMatch.Result match, List<VocalDiff.Spot> spots) {
    }

    public record Verdict(boolean censored, Heard uncensored, int words) {

        static final Verdict CLEAN = new Verdict(false, null, 0);
    }

    private record Span(long from, long to) {
    }

    // The censored version is the odd one out, but two censored uploads outvote one clean one,
    // so a difference only counts with a sign of which side lost the word.
    public static Verdict judge(List<Heard> heard, long durationMs) {
        List<Span> takes = takes(heard);
        List<Heard> comparable = heard.stream().filter(h -> comparable(h, takes, durationMs)).toList();
        for (Heard h : comparable) {
            if (trusted(h) && words(h, takes).isEmpty()) return Verdict.CLEAN;
        }

        Heard best = comparable.stream()
                .filter(h -> !words(h, takes).isEmpty() && !censoredItself(h, takes) && evidence(h, comparable, takes))
                .max(Comparator.comparingDouble(VariantJudge::preference))
                .orElse(null);
        return best == null ? Verdict.CLEAN : new Verdict(true, best, words(best, takes).size());
    }

    public static boolean cleanCopy(Heard h, List<Heard> all) {
        return trusted(h) && h.match().aligned() && words(h, takes(all)).isEmpty();
    }

    static boolean comparable(Heard h, List<Span> takes, long durationMs) {
        if (!h.match().sameSong()) return false;
        List<VocalDiff.Spot> words = words(h, takes);
        long changed = 0;
        for (VocalDiff.Spot spot : words) changed += spot.lengthMs();
        long longer = h.spots().stream().filter(s -> s.lengthMs() > WORD_MS).count();
        return words.size() <= MAX_SPOTS && longer <= 2 && (durationMs <= 0 || changed <= durationMs * MAX_SHARE);
    }

    // A passage some version sings differently all through is another take of it, not censorship,
    // and whatever any version differs in there says nothing about words.
    static List<Span> takes(List<Heard> heard) {
        List<Span> spans = new ArrayList<>();
        for (Heard h : heard) {
            List<VocalDiff.Spot> sung = h.spots().stream()
                    .filter(s -> s.levelDb() > MUTED_DB && s.levelDb() < -MUTED_DB).toList();
            for (int i = 0; i + TAKE_SPOTS - 1 < sung.size(); i++) {
                VocalDiff.Spot first = sung.get(i);
                VocalDiff.Spot last = sung.get(i + TAKE_SPOTS - 1);
                if (last.atMs() - first.atMs() > TAKE_MS) continue;
                int end = i + TAKE_SPOTS - 1;
                while (end + 1 < sung.size() && sung.get(end + 1).atMs() - sung.get(end).atMs() <= TAKE_MS / 2) end++;
                VocalDiff.Spot tail = sung.get(end);
                spans.add(new Span(first.atMs() - 1_000, tail.atMs() + tail.lengthMs() + 1_000));
                i = end;
            }
        }
        return spans;
    }

    private static List<VocalDiff.Spot> words(Heard h, List<Span> takes) {
        return h.spots().stream()
                .filter(s -> s.lengthMs() <= WORD_MS && Math.abs(s.levelDb()) <= SILENT_DB)
                .filter(s -> takes.stream().noneMatch(t -> s.atMs() >= t.from() && s.atMs() <= t.to()))
                .toList();
    }

    private static boolean censoredItself(Heard h, List<Span> takes) {
        List<VocalDiff.Spot> words = words(h, takes);
        long louderHere = words.stream().filter(s -> s.levelDb() >= -MUTED_DB).count();
        return louderHere * 2 > words.size();
    }

    private static boolean evidence(Heard h, List<Heard> all, List<Span> takes) {
        List<VocalDiff.Spot> words = words(h, takes);
        long mutedHere = words.stream().filter(s -> s.levelDb() <= MUTED_DB).count();
        if (mutedHere * 2 >= words.size()) return true;
        if (trusted(h)) return true;
        for (Heard other : all) {
            if (other == h || !trusted(other) || censoredItself(other, takes)) continue;
            if (shared(words, words(other, takes)) * 2 >= words.size()) return true;
        }
        return false;
    }

    private static boolean trusted(Heard h) {
        return VariantFinder.SOUNDCLOUD.equals(h.hit().source()) || VariantFinder.markedUncensored(h.hit().title());
    }

    private static int shared(List<VocalDiff.Spot> a, List<VocalDiff.Spot> b) {
        int count = 0;
        for (VocalDiff.Spot x : a) {
            for (VocalDiff.Spot y : b) {
                if (Math.abs(x.atMs() - y.atMs()) <= NEAR_MS) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static double preference(Heard h) {
        double score = h.match().score();
        if (h.match().aligned()) score += 4;
        if (h.match().speed() == 1) score += 2;
        if (VariantFinder.SOUNDCLOUD.equals(h.hit().source())) score += 1;
        return score;
    }
}
