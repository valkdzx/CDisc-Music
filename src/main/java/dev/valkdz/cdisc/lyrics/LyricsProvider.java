package dev.valkdz.cdisc.lyrics;

public interface LyricsProvider {

    String id();

    SyncedLyrics fetch(LyricsQuery query) throws Exception;
}
