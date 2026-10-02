package dev.valkdz.cdisc.audio.player;

public abstract class AudioEventAdapter {

    public void onTrackStart(AudioPlayer player, AudioTrack track) {
    }

    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason reason) {
    }

    public void onTrackException(AudioPlayer player, AudioTrack track, LoadException exception) {
    }

    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
    }
}
