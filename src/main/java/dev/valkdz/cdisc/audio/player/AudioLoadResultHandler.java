package dev.valkdz.cdisc.audio.player;

public interface AudioLoadResultHandler {

    void trackLoaded(AudioTrack track);

    void playlistLoaded(AudioPlaylist playlist);

    void noMatches();

    void loadFailed(LoadException exception);
}
