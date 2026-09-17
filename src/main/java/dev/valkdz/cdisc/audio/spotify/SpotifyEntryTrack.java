package dev.valkdz.cdisc.audio.spotify;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

public final class SpotifyEntryTrack extends DelegatedAudioTrack {

    public SpotifyEntryTrack(SpotifyBridge.Entry entry) {
        this(new AudioTrackInfo(entry.title(), entry.artist(), entry.durationMs(),
                entry.trackId(), false, entry.spotifyUrl()));
    }

    private SpotifyEntryTrack(AudioTrackInfo info) {
        super(info);
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) {
        throw new FriendlyException("A playlist entry is written to a disc, not played",
                FriendlyException.Severity.COMMON, null);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new SpotifyEntryTrack(trackInfo);
    }
}
