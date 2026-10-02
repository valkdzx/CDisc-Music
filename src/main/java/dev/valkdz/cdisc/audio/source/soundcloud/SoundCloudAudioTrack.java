package dev.valkdz.cdisc.audio.source.soundcloud;

import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.HttpStream;
import dev.valkdz.cdisc.audio.player.Playback;
import dev.valkdz.cdisc.audio.source.hls.HlsPlaylist;
import dev.valkdz.cdisc.audio.source.hls.HlsStream;

public final class SoundCloudAudioTrack extends AudioTrack {

    private final SoundCloudSourceManager sourceManager;
    private final String protocol;
    private final String mimeType;
    private final String authorization;

    SoundCloudAudioTrack(AudioTrackInfo trackInfo, SoundCloudSourceManager sourceManager, String protocol,
                         String mimeType, String authorization) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.protocol = protocol;
        this.mimeType = mimeType;
        this.authorization = authorization;
    }

    // The stream address is signed with an expiry, so every start asks for a fresh one.
    @Override
    public void process(Playback playback) throws Exception {
        String url = sourceManager.streamUrl(trackInfo.identifier, authorization);
        if ("hls".equals(protocol)) {
            HlsPlaylist playlist = HlsPlaylist.fetch(url);
            int index = playlist.indexAt(playback.startMs());
            playback.decode(new HlsStream(playlist, index), mimeType, playlist.segments().get(index).startMs());
            return;
        }
        playback.decode(new HttpStream(url, -1, 0,
                () -> sourceManager.streamUrl(trackInfo.identifier, authorization)), mimeType);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new SoundCloudAudioTrack(trackInfo, sourceManager, protocol, mimeType, authorization);
    }
}
