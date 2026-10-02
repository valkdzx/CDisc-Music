package dev.valkdz.cdisc.audio.source.hls;

import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.audio.player.Playback;

import java.io.IOException;

public final class HlsVodAudioTrack extends AudioTrack {

    private final AudioSourceManager sourceManager;
    private final String playlistUrl;
    private final String mimeType;
    private final Http.Guard guard;

    public HlsVodAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager, String playlistUrl,
                            String mimeType) {
        this(trackInfo, sourceManager, playlistUrl, mimeType, null);
    }

    public HlsVodAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager, String playlistUrl,
                            String mimeType, Http.Guard guard) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.playlistUrl = playlistUrl;
        this.mimeType = mimeType;
        this.guard = guard;
    }

    @Override
    public void process(Playback playback) throws Exception {
        HlsPlaylist playlist = HlsPlaylist.fetch(playlistUrl, guard);
        int index = playlist.indexAt(playback.startMs());
        // Decoding restarts at a segment boundary; the pipeline drops audio up to the asked position.
        playback.decode(new HlsStream(playlist, index), mimeType, playlist.segments().get(index).startMs());
    }

    public static HlsStream openWhole(String playlistUrl) throws IOException {
        return new HlsStream(HlsPlaylist.fetch(playlistUrl), 0);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new HlsVodAudioTrack(trackInfo, sourceManager, playlistUrl, mimeType, guard);
    }
}
