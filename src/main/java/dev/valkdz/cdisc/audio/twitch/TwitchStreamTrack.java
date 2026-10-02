package dev.valkdz.cdisc.audio.twitch;

import dev.valkdz.cdisc.audio.hls.HlsStream;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Playback;

public final class TwitchStreamTrack extends AudioTrack {

    private final TwitchSourceManager sourceManager;
    private final String channelName;

    public TwitchStreamTrack(AudioTrackInfo trackInfo, TwitchSourceManager sourceManager, String channelName) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.channelName = channelName;
    }

    @Override
    public void process(Playback playback) throws Exception {
        String playlist = sourceManager.mediaPlaylist(channelName);
        playback.decode(HlsStream.live(playlist, () -> sourceManager.mediaPlaylist(channelName), null), null);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new TwitchStreamTrack(trackInfo, sourceManager, channelName);
    }
}
