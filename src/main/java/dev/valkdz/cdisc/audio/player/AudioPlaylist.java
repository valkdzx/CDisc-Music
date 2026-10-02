package dev.valkdz.cdisc.audio.player;

import java.util.List;

public final class AudioPlaylist implements AudioItem {

    private final String name;
    private final List<AudioTrack> tracks;
    private final AudioTrack selected;
    private final boolean searchResult;

    public AudioPlaylist(String name, List<AudioTrack> tracks, AudioTrack selected, boolean searchResult) {
        this.name = name;
        this.tracks = tracks;
        this.selected = selected;
        this.searchResult = searchResult;
    }

    public String getName() {
        return name;
    }

    public List<AudioTrack> getTracks() {
        return tracks;
    }

    public AudioTrack getSelectedTrack() {
        return selected;
    }

    public boolean isSearchResult() {
        return searchResult;
    }
}
