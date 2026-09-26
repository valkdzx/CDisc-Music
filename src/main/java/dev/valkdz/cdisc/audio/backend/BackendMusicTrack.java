package dev.valkdz.cdisc.audio.backend;

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.valkdz.cdisc.audio.hls.HlsVodAudioTrack;
import dev.valkdz.cdisc.audio.sabr.DirectAudioTrack;

import java.io.IOException;
import java.io.InputStream;

public final class BackendMusicTrack extends DelegatedAudioTrack {

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
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        BackendMusicSourceManager.Stream fresh = freshStream();

        if (fresh.hls()) {
            processDelegate(new HlsVodAudioTrack(trackInfo, sourceManager, sourceManager.interfaces(),
                    fresh.url()), executor);
        } else {
            processDelegate(new DirectAudioTrack(trackInfo, sourceManager, sourceManager.interfaces(),
                    fresh.url(), fresh.mimeType(), -1), executor);
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
                ? new Download(null, "audio/mpeg", HlsVodAudioTrack.openWhole(sourceManager.interfaces(), fresh.url()))
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
