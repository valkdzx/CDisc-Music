package dev.valkdz.cdisc.lyrics;

import java.util.ArrayList;
import java.util.List;

public final class LyricsRenderer {

    public static final String BREAK_MARKER = "♪";

    private LyricsRenderer() {
    }

    public record Options(int before, int after, LyricsStyle style,
                          long countdownMs, String filledDot, String emptyDot,
                          float fade) {
    }

    public static int countdownStep(SyncedLyrics lyrics, long positionMs, long countdownMs) {
        if (lyrics == null || lyrics.size() == 0 || countdownMs <= 0) return 0;

        long firstCue = lyrics.lines().get(0).timeMs();
        long remaining = firstCue - positionMs;
        if (remaining <= 0 || remaining > countdownMs) return 0;

        int steps = Math.max(1, (int) Math.ceil(countdownMs / 1000.0));
        long elapsed = countdownMs - remaining;
        int step = (int) (elapsed * steps / countdownMs) + 1;
        return Math.max(1, Math.min(steps, step));
    }

    public static List<String> window(SyncedLyrics lyrics, long positionMs, Options options) {
        List<String> out = new ArrayList<>(options.before() + options.after() + 2);
        if (lyrics == null || lyrics.size() == 0) return out;

        int current = lyrics.indexAt(positionMs);

        int first;
        int last;
        if (current < 0) {
            int step = countdownStep(lyrics, positionMs, options.countdownMs());
            first = 0;

            last = Math.min(lyrics.size() - 1, options.after() - (step > 0 ? 1 : 0));
            if (step > 0) {
                out.add(countdownLine(options, step));
            }
        } else {
            first = Math.max(0, current - options.before());
            last = Math.min(lyrics.size() - 1, current + options.after());
        }

        for (int i = first; i <= last; i++) {
            SyncedLyrics.Line line = lyrics.lines().get(i);
            String text = line.isBlank() ? BREAK_MARKER : line.text();
            out.add(colorFor(options, i, current) + text);
        }
        return out;
    }

    private static String colorFor(Options options, int index, int current) {
        LyricsStyle style = options.style();
        if (current < 0) return style.other();

        if (index == current) return style.rising(options.fade());
        if (index == current - 1) return style.falling(options.fade());
        return style.other();
    }

    private static String countdownLine(Options options, int step) {
        int steps = Math.max(1, (int) Math.ceil(options.countdownMs() / 1000.0));

        StringBuilder dots = new StringBuilder();
        for (int i = 1; i <= steps; i++) {
            if (i > 1) dots.append(' ');
            dots.append(i <= step ? options.style().current() + options.filledDot()
                    : options.style().other() + options.emptyDot());
        }
        return dots.toString();
    }
}
