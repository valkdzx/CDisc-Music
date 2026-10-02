package dev.valkdz.cdisc.audio.backend;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.valkdz.cdisc.audio.sabr.ChunkedHttpStream;

import java.net.URI;

// lavaplayer's own HTTP track asks for bytes=N- on every open, and googlevideo throttles
// any range past 10 MB to ~30 KB/s, which stalls seeks and can starve playback itself.
public final class RangedHttpAudioTrack extends HttpAudioTrack {

    private final MediaContainerDescriptor container;
    private final HttpAudioSourceManager source;
    private final long contentLength;

    public RangedHttpAudioTrack(AudioTrackInfo trackInfo, MediaContainerDescriptor container,
                                HttpAudioSourceManager source, long contentLength) {
        super(trackInfo, container, source);
        this.container = container;
        this.source = source;
        this.contentLength = contentLength;
    }

    public static long contentLengthOf(String url) {
        if (url == null || !url.contains(".googlevideo.com/")) return -1;
        int query = url.indexOf('?');
        if (query < 0) return -1;

        for (String pair : url.substring(query + 1).split("&")) {
            if (!pair.startsWith("clen=")) continue;
            try {
                return Long.parseLong(pair.substring(5));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        try (HttpInterface http = source.getHttpInterface();
             ChunkedHttpStream stream = new ChunkedHttpStream(http, new URI(trackInfo.identifier), contentLength)) {
            processDelegate((InternalAudioTrack) container.createTrack(trackInfo, stream), executor);
        }
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new RangedHttpAudioTrack(trackInfo, container, source, contentLength);
    }
}
