package dev.valkdz.cdisc.audio.spotify;

import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.LazyTrack;

public final class SpotifyEntryTrack extends LazyTrack {

    private static final AudioSourceManager SOURCE = AudioSourceManager.named("spotify");

    private final Resolver resolver;

    public SpotifyEntryTrack(SpotifyBridge.Entry entry, Resolver resolver) {
        this(new AudioTrackInfo(entry.title(), entry.artist(), entry.durationMs(),
                entry.trackId(), false, entry.spotifyUrl()), resolver);
    }

    private SpotifyEntryTrack(AudioTrackInfo info, Resolver resolver) {
        super(info, SOURCE, resolver);
        this.resolver = resolver;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new SpotifyEntryTrack(trackInfo, resolver);
    }
}
