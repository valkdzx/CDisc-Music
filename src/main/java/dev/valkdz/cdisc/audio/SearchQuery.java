package dev.valkdz.cdisc.audio;

public record SearchQuery(String text, int requestedResults) {

    private static final int MIN_RESULTS = 1;
    private static final int MAX_RESULTS = 10;

    public static final int UNSET = 0;

    public static SearchQuery parse(String raw) {
        String query = raw == null ? "" : raw.trim();
        if (query.isEmpty() || isLink(query)) {
            return new SearchQuery(query, UNSET);
        }

        int colon = query.lastIndexOf(':');
        if (colon <= 0 || colon == query.length() - 1) {
            return new SearchQuery(query, UNSET);
        }

        String tail = query.substring(colon + 1).trim();
        int count;
        try {
            count = Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            return new SearchQuery(query, UNSET);
        }
        if (count < MIN_RESULTS || count > MAX_RESULTS) {
            return new SearchQuery(query, UNSET);
        }

        String head = query.substring(0, colon).trim();

        if (head.isEmpty() || head.endsWith(":")) {
            return new SearchQuery(query, UNSET);
        }
        return new SearchQuery(head, count);
    }

    private static boolean isLink(String query) {
        return query.regionMatches(true, 0, "http://", 0, 7)
                || query.regionMatches(true, 0, "https://", 0, 8);
    }
}
