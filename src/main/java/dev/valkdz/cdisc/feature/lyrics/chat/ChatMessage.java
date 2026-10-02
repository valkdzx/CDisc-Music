package dev.valkdz.cdisc.feature.lyrics.chat;

public record ChatMessage(String author, String color, String text) {

    public static ChatMessage of(String author, String color, String text, int maxLength) {
        String name = clean(author);
        String words = clean(text);
        if (name.isEmpty() || words.isEmpty()) return null;

        if (maxLength > 0 && words.codePointCount(0, words.length()) > maxLength) {
            words = words.substring(0, words.offsetByCodePoints(0, maxLength)).trim() + "…";
        }
        return new ChatMessage(name, color, words);
    }

    private static String clean(String raw) {
        if (raw == null) return "";
        return raw.replace('§', ' ').replaceAll("\\s+", " ").trim();
    }
}
