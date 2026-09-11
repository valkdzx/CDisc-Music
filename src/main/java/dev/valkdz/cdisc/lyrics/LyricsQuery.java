package dev.valkdz.cdisc.lyrics;

import java.util.Locale;
import java.util.regex.Pattern;

public record LyricsQuery(String artist, String track, long durationMs, LyricsQuery fallback) {

    private static final Pattern NOISE_BRACKETS = Pattern.compile(
            "[(\\[{]\\s*(?:[^()\\[\\]{}]*\\b(?:official|video|audio|lyrics?|lyric|visuali[sz]er|"
                    + "mv|hd|hq|4k|8k|full|version|remaster(?:ed)?|explicit|clean|clip|premiere|"
                    + "prod\\.?|produced|slowed|reverb|sped\\s*up|cover\\s*art|"
                    + "\u043a\u043b\u0438\u043f|\u0432\u0438\u0434\u0435\u043e|\u0442\u0435\u043a\u0441\u0442|"
                    + "\u0441\u043b\u043e\u0432\u0430|\u043f\u0440\u0435\u043c\u044c\u0435\u0440\u0430|"
                    + "\u043e\u0444\u0438\u0446\u0438\u0430\u043b\u044c\u043d\\p{L}*|\u0430\u0443\u0434\u0438\u043e)"
                    + "\\b[^()\\[\\]{}]*)\\s*[)\\]}]",

            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern FEATURING = Pattern.compile(
            "\\s*[(\\[]?\\s*\\b(?:feat|ft|featuring)\\b\\.?\\s*[^()\\[\\]]*[)\\]]?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern TOPIC_SUFFIX = Pattern.compile("\\s*-\\s*Topic\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern ARTIST_SPLIT = Pattern.compile("\\s+[-\u2013\u2014|]\\s+");

    private static final Pattern PLACEHOLDER_AUTHOR =
            Pattern.compile("^(?:unknown|unknown\\s+artist|various\\s+artists|no\\s+name|n/a|-)$",
                    Pattern.CASE_INSENSITIVE);

    public static LyricsQuery of(String rawAuthor, String rawTitle, long durationMs) {
        String artist = cleanArtist(rawAuthor);
        String track = cleanTitle(rawTitle);

        LyricsQuery asGiven = new LyricsQuery(artist, track, durationMs, null);

        String[] split = ARTIST_SPLIT.split(track, 2);
        if (split.length == 2 && !split[0].isBlank() && !split[1].isBlank()) {
            return new LyricsQuery(tidy(split[0]), tidy(split[1]), durationMs, asGiven);
        }

        return asGiven;
    }

    public boolean isUsable() {
        return !track.isBlank();
    }

    public int durationSeconds() {
        return durationMs > 0 && durationMs != Long.MAX_VALUE ? (int) (durationMs / 1000L) : 0;
    }

    public LyricsQuery withoutFeatures() {
        String strippedTrack = tidy(FEATURING.matcher(track).replaceAll(" "));
        String strippedArtist = tidy(FEATURING.matcher(artist).replaceAll(" "));
        if (strippedTrack.equals(track) && strippedArtist.equals(artist)) return null;
        if (strippedTrack.isBlank()) return null;

        return new LyricsQuery(strippedArtist, strippedTrack, durationMs, null);
    }

    public String cacheKey() {
        return artist.toLowerCase(Locale.ROOT) + " " + track.toLowerCase(Locale.ROOT);
    }

    private static String cleanArtist(String raw) {
        if (raw == null) return "";
        String artist = TOPIC_SUFFIX.matcher(raw).replaceAll("");
        artist = NOISE_BRACKETS.matcher(artist).replaceAll(" ");
        artist = tidy(artist);
        return PLACEHOLDER_AUTHOR.matcher(artist).matches() ? "" : artist;
    }

    private static String cleanTitle(String raw) {
        if (raw == null) return "";
        String title = NOISE_BRACKETS.matcher(raw).replaceAll(" ");

        title = title.replaceAll("[(\\[{]\\s*[)\\]}]", " ");
        return tidy(title);
    }

    private static String tidy(String text) {
        String tidied = text.replaceAll("\\s+", " ").trim();
        tidied = tidied.replaceAll("^[\\p{Pd}|,;:.]+\\s*", "");
        tidied = tidied.replaceAll("\\s*[\\p{Pd}|,;:]+$", "");
        return tidied.trim();
    }
}
