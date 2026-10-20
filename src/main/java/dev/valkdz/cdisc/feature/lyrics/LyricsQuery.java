package dev.valkdz.cdisc.feature.lyrics;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public record LyricsQuery(String artist, String track, long durationMs, LyricsQuery fallback) {

    private static final Pattern NOISE_BRACKETS = Pattern.compile(
            "[(\\[{]\\s*(?:[^()\\[\\]{}]*\\b(?:official|video|audio|lyrics?|lyric|visuali[sz]er|"
                    + "mv|hd|hq|4k|8k|full|version|remaster(?:ed)?|explicit|clean|clip|premiere|"
                    + "kbps|flac|mp3|lossless|bitrate|\u043a\u0430\u0447\u0435\u0441\u0442\u0432\\p{L}*|"
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

    // A dash glued to one word still parts the artist from the song; a plain hyphen never does.
    private static final Pattern ARTIST_SPLIT = Pattern.compile("\\s+[-\u2013\u2014|]\\s+|\\s+[\u2013\u2014]|[\u2013\u2014]\\s+");

    private static final Pattern EMPTY_BRACKETS = Pattern.compile("[(\\[{]\\s*[)\\]}]");

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final Pattern LEADING_PUNCTUATION = Pattern.compile("^[\\p{Pd}|,;:.]+\\s*");

    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("\\s*[\\p{Pd}|,;:]+$");

    private static final Pattern PLACEHOLDER_AUTHOR =
            Pattern.compile("^(?:unknown|unknown\\s+artist|various\\s+artists|no\\s+name|n/a|-)$",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern BRACKETED = Pattern.compile("[(\\[{][^()\\[\\]{}]*[)\\]}]");

    private static final Pattern CREDIT_SPLIT = Pattern.compile("\\s*(?:[,&/]|\\b(?:feat|ft|featuring)\\b\\.?)\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final Pattern VOWELS = Pattern.compile("[aeiouy]");

    private static final Pattern REPEATS = Pattern.compile("(.)\\1+");

    private static final String[] CYRILLIC = {"a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m",
            "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"};

    private static final long UNCREDITED = 1000;

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

    public String primaryArtist() {
        for (String name : CREDIT_SPLIT.split(artist)) {
            if (!name.isBlank()) return name.trim();
        }
        return artist;
    }

    public long rank(String title, String credits, long gapSeconds) {
        if (!titleMatches(title)) return -1;
        boolean credited = creditsMatch(credits);
        if (!credited && durationSeconds() == 0 && !artist.isBlank()) return -1;
        return gapSeconds + (credited ? 0 : UNCREDITED);
    }

    boolean titleMatches(String candidate) {
        if (candidate == null) return false;
        String wanted = titleKey(track);
        String found = titleKey(candidate);
        if (wanted.isEmpty() || found.isEmpty()) return false;
        return containsWords(found, wanted) || containsWords(wanted, found);
    }

    boolean creditsMatch(String candidate) {
        if (candidate == null || artist.isBlank()) return false;
        for (String asked : CREDIT_SPLIT.split(artist)) {
            String wanted = skeleton(asked);
            if (wanted.isEmpty()) continue;
            for (String credited : CREDIT_SPLIT.split(candidate)) {
                String found = skeleton(credited);
                if (!found.isEmpty() && (containsWords(found, wanted) || containsWords(wanted, found))) return true;
            }
        }
        return false;
    }

    private static String titleKey(String title) {
        String key = latin(FEATURING.matcher(BRACKETED.matcher(title).replaceAll(" ")).replaceAll(" "));
        return key.isEmpty() ? latin(title) : key;
    }

    private static String skeleton(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : latin(name).split(" ")) {
            String bare = REPEATS.matcher(VOWELS.matcher(word).replaceAll("")).replaceAll("$1");
            if (bare.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(bare);
        }
        return out.toString();
    }

    private static String latin(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= '\u0430' && c <= '\u044f') out.append(CYRILLIC[c - '\u0430']);
            else if (c == '\u0451') out.append('e');
            else if (c == '\u0456') out.append('i');
            else if (c == '\u0457') out.append("yi");
            else if (c == '\u0454') out.append("ye");
            else if (c == '\u0491') out.append('g');
            else out.append(c);
        }
        // Transliterate before stripping marks: NFD would split the Cyrillic short i and yo into a letter and a mark.
        String plain = MARKS.matcher(Normalizer.normalize(out, Normalizer.Form.NFD)).replaceAll("");
        return NOT_WORD.matcher(plain).replaceAll(" ").trim();
    }

    private static boolean containsWords(String longer, String shorter) {
        return (" " + longer + " ").contains(" " + shorter + " ");
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

        title = EMPTY_BRACKETS.matcher(title).replaceAll(" ");
        return tidy(title);
    }

    private static String tidy(String text) {
        String tidied = SPACES.matcher(text).replaceAll(" ").trim();
        tidied = LEADING_PUNCTUATION.matcher(tidied).replaceAll("");
        tidied = TRAILING_PUNCTUATION.matcher(tidied).replaceAll("");
        return tidied.trim();
    }
}
