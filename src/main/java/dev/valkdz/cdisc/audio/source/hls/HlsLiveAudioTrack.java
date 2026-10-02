package dev.valkdz.cdisc.audio.source.hls;

import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.audio.player.Playback;

public final class HlsLiveAudioTrack extends AudioTrack {

    private final AudioSourceManager sourceManager;
    private final String playlistUrl;
    private final Http.Guard guard;

    public HlsLiveAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager, String playlistUrl,
                             Http.Guard guard) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.playlistUrl = playlistUrl;
        this.guard = guard;
    }

    @Override
    public void process(Playback playback) throws Exception {
        String media = HlsPlaylist.fetch(playlistUrl, guard).url();
        playback.decode(HlsStream.live(media, null, guard), null);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new HlsLiveAudioTrack(trackInfo, sourceManager, playlistUrl, guard);
    }
}
