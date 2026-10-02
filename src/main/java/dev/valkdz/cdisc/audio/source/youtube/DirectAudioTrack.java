package dev.valkdz.cdisc.audio.source.youtube;

import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.HttpAudioTrack;

import java.util.concurrent.Callable;

public final class DirectAudioTrack extends HttpAudioTrack {

    private final AudioSourceManager sourceManager;
    private final String url;
    private final String mimeType;
    private final long contentLength;
    private final Callable<String> refresh;

    public DirectAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager, String url,
                            String mimeType, long contentLength, Callable<String> refresh) {
        super(trackInfo, sourceManager, url, mimeType, contentLength, refresh, null);
        this.sourceManager = sourceManager;
        this.url = url;
        this.mimeType = mimeType;
        this.contentLength = contentLength;
        this.refresh = refresh;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new DirectAudioTrack(trackInfo, sourceManager, url, mimeType, contentLength, refresh);
    }
}
