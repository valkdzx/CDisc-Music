package dev.valkdz.cdisc.feature.lyrics;

public interface LyricsProvider {

    String id();

    SyncedLyrics fetch(LyricsQuery query) throws Exception;
}
