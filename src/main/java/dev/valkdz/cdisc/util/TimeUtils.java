package dev.valkdz.cdisc.util;

public class TimeUtils {

    public static String format(long millis) {
        if (millis < 0 || millis == Long.MAX_VALUE) return "??:??";

        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    public static String formatProgress(long positionMillis, long durationMillis) {
        return format(positionMillis) + " / " + format(durationMillis);
    }

    public static String formatCompact(long millis) {
        if (millis < 0 || millis == Long.MAX_VALUE) return "0:00";

        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    public static String formatCompactProgress(long positionMillis, long durationMillis) {
        return formatCompact(positionMillis) + " / " + formatCompact(durationMillis);
    }

    public static Long parseTimecode(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;

        String[] parts = trimmed.split(":");
        if (parts.length < 1 || parts.length > 3) return null;

        try {
            if (parts.length == 1) {

                long totalSeconds = Long.parseLong(parts[0]);
                if (totalSeconds < 0) return null;
                return totalSeconds * 1000L;
            }

            long hours = 0, minutes, seconds;
            if (parts.length == 3) {
                hours = Long.parseLong(parts[0]);
                minutes = Long.parseLong(parts[1]);
                seconds = Long.parseLong(parts[2]);
            } else {
                minutes = Long.parseLong(parts[0]);
                seconds = Long.parseLong(parts[1]);
            }

            if (hours < 0 || minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59) return null;

            return (hours * 3600 + minutes * 60 + seconds) * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
