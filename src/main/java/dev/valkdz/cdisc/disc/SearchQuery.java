package dev.valkdz.cdisc.disc;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SearchQuery(String text, int requestedResults, String version, String badFlag) {

    private static final int MIN_RESULTS = 1;
    public static final int MAX_RESULTS = 10;

    public static final int UNSET = 0;

    public static final String RESULTS_FLAG = "--results=";
    public static final String VERSION_FLAG = "--version=";

    private static final Pattern FLAG = Pattern.compile("(?:^|\\s+)--(results|version)=(\\S*)",
            Pattern.CASE_INSENSITIVE);

    public static SearchQuery parse(String raw) {
        String query = raw == null ? "" : raw.trim();
        int results = UNSET;
        String version = null;
        String bad = null;

        Matcher flag = FLAG.matcher(query);
        StringBuilder rest = new StringBuilder();
        while (flag.find()) {
            String value = flag.group(2).toLowerCase(Locale.ROOT);
            if (flag.group(1).equalsIgnoreCase("results")) {
                int count = count(value);
                if (count == UNSET) bad = flag.group().trim();
                else results = count;
            } else {
                String kind = versionOf(value);
                if (kind == null) bad = flag.group().trim();
                else version = kind.isEmpty() ? null : kind;
            }
            flag.appendReplacement(rest, "");
        }
        flag.appendTail(rest);
        String text = rest.toString().trim();

        if (results != UNSET || text.isEmpty() || isLink(text)) {
            return new SearchQuery(text, results, version, bad);
        }
        return trailingCount(text, version, bad);
    }

    // The older spelling: a trailing :N on a search.
    private static SearchQuery trailingCount(String query, String version, String bad) {
        int colon = query.lastIndexOf(':');
        if (colon <= 0 || colon == query.length() - 1) {
            return new SearchQuery(query, UNSET, version, bad);
        }

        int count = count(query.substring(colon + 1).trim());
        String head = query.substring(0, colon).trim();
        if (count == UNSET || head.isEmpty() || head.endsWith(":")) {
            return new SearchQuery(query, UNSET, version, bad);
        }
        return new SearchQuery(head, count, version, bad);
    }

    private static int count(String raw) {
        try {
            int count = Integer.parseInt(raw);
            return count < MIN_RESULTS || count > MAX_RESULTS ? UNSET : count;
        } catch (NumberFormatException e) {
            return UNSET;
        }
    }

    // "" is the original, which needs nothing kept on the disc; null is a word we do not know.
    private static String versionOf(String value) {
        return switch (value) {
            case "original" -> "";
            case "uncensored" -> "uncensored";
            case "instrumental", "minus" -> "minus";
            default -> null;
        };
    }

    private static boolean isLink(String query) {
        return query.regionMatches(true, 0, "http://", 0, 7)
                || query.regionMatches(true, 0, "https://", 0, 8);
    }
}
