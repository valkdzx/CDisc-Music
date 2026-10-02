package dev.valkdz.cdisc.audio.media;

public record Tags(String title, String artist) {

    public static final Tags NONE = new Tags(null, null);

    public Tags orElse(Tags other) {
        return new Tags(title != null ? title : other.title, artist != null ? artist : other.artist);
    }

    public static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.replace("\u0000", "").trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
