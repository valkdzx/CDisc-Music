package dev.valkdz.cdisc.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SyncedLyrics {

    private static final Pattern TIMESTAMP =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    private static final Pattern OFFSET_TAG =
            Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

    public record Line(long timeMs, String text) {

        public boolean isBlank() {
            return text.isEmpty();
        }
    }

    private final List<Line> lines;

    private SyncedLyrics(List<Line> lines) {
        this.lines = lines;
    }

    public List<Line> lines() {
        return lines;
    }

    public int size() {
        return lines.size();
    }

    public int indexAt(long positionMs) {
        int low = 0;
        int high = lines.size() - 1;
        int found = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).timeMs() <= positionMs) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    public static SyncedLyrics parse(String lrc) {
        if (lrc == null || lrc.isBlank()) return null;

        long offset = readOffset(lrc);
        List<Line> parsed = new ArrayList<>();

        for (String raw : lrc.split("\\r?\\n")) {
            Matcher matcher = TIMESTAMP.matcher(raw);

            int textStart = 0;
            List<Long> stamps = new ArrayList<>();
            while (matcher.find() && matcher.start() == textStart) {
                stamps.add(toMillis(matcher));
                textStart = matcher.end();
            }
            if (stamps.isEmpty()) continue;

            String text = raw.substring(textStart).trim();
            for (long stamp : stamps) {
                parsed.add(new Line(Math.max(0, stamp + offset), text));
            }
        }

        if (parsed.isEmpty()) return null;

        parsed.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return new SyncedLyrics(Collections.unmodifiableList(parsed));
    }

    private static long toMillis(Matcher matcher) {
        long minutes = Long.parseLong(matcher.group(1));
        long seconds = Long.parseLong(matcher.group(2));

        long fraction = 0;
        String digits = matcher.group(3);
        if (digits != null) {

            long value = Long.parseLong(digits);
            fraction = switch (digits.length()) {
                case 1 -> value * 100;
                case 2 -> value * 10;
                default -> value;
            };
        }
        return (minutes * 60 + seconds) * 1000L + fraction;
    }

    private static long readOffset(String lrc) {
        Matcher matcher = OFFSET_TAG.matcher(lrc);
        if (!matcher.find()) return 0L;
        try {
            return -Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
