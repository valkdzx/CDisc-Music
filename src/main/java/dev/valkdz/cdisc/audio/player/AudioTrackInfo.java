package dev.valkdz.cdisc.audio.player;

public final class AudioTrackInfo {

    public static final long UNKNOWN_LENGTH = Long.MAX_VALUE;
    public static final String UNKNOWN_TITLE = "Unknown title";
    public static final String UNKNOWN_ARTIST = "Unknown artist";

    public final String title;
    public final String author;
    public final long length;
    public final String identifier;
    public final boolean isStream;
    public final String uri;
    public final String artworkUrl;
    public final String isrc;

    public AudioTrackInfo(String title, String author, long length, String identifier, boolean isStream, String uri,
                          String artworkUrl, String isrc) {
        this.title = title;
        this.author = author;
        this.length = length;
        this.identifier = identifier;
        this.isStream = isStream;
        this.uri = uri;
        this.artworkUrl = artworkUrl;
        this.isrc = isrc;
    }

    public AudioTrackInfo(String title, String author, long length, String identifier, boolean isStream, String uri) {
        this(title, author, length, identifier, isStream, uri, null, null);
    }
}
