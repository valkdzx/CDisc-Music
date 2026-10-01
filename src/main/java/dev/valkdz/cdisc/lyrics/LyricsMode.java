package dev.valkdz.cdisc.lyrics;

import dev.valkdz.cdisc.util.TimeUtils;

public enum LyricsMode {

    // Stored on players as these codes, so an existing number never changes meaning.
    OFF(0, "off", false, false, false),
    LYRICS(1, "lyrics", true, false, false),
    TRACK_LYRICS(2, "track_lyrics", true, true, true),
    TIME_LYRICS(5, "time_lyrics", true, true, false),
    TRACK(3, "track", false, true, true),
    TIME(4, "time", false, true, false);

    private final byte code;
    private final String key;
    private final boolean lyrics;
    private final boolean header;
    private final boolean title;

    LyricsMode(int code, String key, boolean lyrics, boolean header, boolean title) {
        this.code = (byte) code;
        this.key = key;
        this.lyrics = lyrics;
        this.header = header;
        this.title = title;
    }

    public byte code() {
        return code;
    }

    public String key() {
        return key;
    }

    public String messageKey() {
        return "gui.lyrics.mode_" + key;
    }

    public boolean showsLyrics() {
        return lyrics;
    }

    public boolean showsHeader() {
        return header;
    }

    public LyricsMode next() {
        LyricsMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public LyricsMode previous() {
        LyricsMode[] all = values();
        return all[(ordinal() + all.length - 1) % all.length];
    }

    public String header(String title, String author, long positionMs, long durationMs) {
        if (!header) return null;

        return withName(title, author, "[" + TimeUtils.formatProgress(positionMs, durationMs) + "]");
    }

    public String liveHeader(String title, String author, long positionMs) {
        if (!header) return null;
        return withName(title, author, "[" + TimeUtils.formatCompact(positionMs) + "]");
    }

    private String withName(String title, String author, String time) {
        if (!this.title) return time;

        String name = author == null || author.isBlank() ? title : title + " - " + author;
        return name == null || name.isBlank() ? time : name + " " + time;
    }

    public static LyricsMode ofCode(byte code) {
        for (LyricsMode mode : values()) {
            if (mode.code == code) return mode;
        }
        return code == 0 ? OFF : LYRICS;
    }

    public static LyricsMode ofKey(String key) {
        for (LyricsMode mode : values()) {
            if (mode.key.equals(key)) return mode;
        }
        return null;
    }
}
