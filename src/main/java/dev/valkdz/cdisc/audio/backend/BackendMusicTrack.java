package dev.valkdz.cdisc.audio.backend;

import dev.valkdz.cdisc.audio.hls.HlsVodAudioTrack;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.HttpAudioTrack;
import dev.valkdz.cdisc.audio.player.Playback;

import java.io.IOException;
import java.io.InputStream;

public final class BackendMusicTrack extends AudioTrack {

    private static final long FRESH_MS = 5 * 60 * 1000L;

    private final BackendMusicSourceManager sourceManager;
    private final BackendMusicSourceManager.Stream stream;
    private final long resolvedAt;

    BackendMusicTrack(AudioTrackInfo trackInfo, BackendMusicSourceManager sourceManager,
                      BackendMusicSourceManager.Stream stream) {
        this(trackInfo, sourceManager, stream, System.currentTimeMillis());
    }

    private BackendMusicTrack(AudioTrackInfo trackInfo, BackendMusicSourceManager sourceManager,
                              BackendMusicSourceManager.Stream stream, long resolvedAt) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.stream = stream;
        this.resolvedAt = resolvedAt;
    }

    @Override
    public void process(Playback playback) throws Exception {
        BackendMusicSourceManager.Stream fresh = freshStream();

        if (fresh.hls()) {
            new HlsVodAudioTrack(trackInfo, sourceManager, fresh.url(), fresh.mimeType()).process(playback);
        } else {
            new HttpAudioTrack(trackInfo, sourceManager, fresh.url(), fresh.mimeType()).process(playback);
        }
    }

    // Both services sign their stream links with an expiry, so a queued or repeated track asks again.
    private BackendMusicSourceManager.Stream freshStream() {
        if (stream != null && System.currentTimeMillis() - resolvedAt <= FRESH_MS) return stream;
        return sourceManager.resolve(trackInfo.uri);
    }

    public record Download(String url, String mimeType, InputStream hls) {}

    public Download openForDownload() throws IOException {
        BackendMusicSourceManager.Stream fresh = freshStream();
        return fresh.hls()
                ? new Download(null, "audio/mpeg", HlsVodAudioTrack.openWhole(fresh.url()))
                : new Download(fresh.url(), fresh.mimeType(), null);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new BackendMusicTrack(trackInfo, sourceManager, stream, resolvedAt);
    }
}
