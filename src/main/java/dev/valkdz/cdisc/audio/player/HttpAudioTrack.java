package dev.valkdz.cdisc.audio.player;

import java.util.concurrent.Callable;

public class HttpAudioTrack extends AudioTrack {

    // googlevideo throttles any range over 10 MB to ~30 KB/s, so its reads go in smaller pieces.
    private static final long GOOGLEVIDEO_CHUNK = 8L * 1024 * 1024;

    private final AudioSourceManager sourceManager;
    private final String url;
    private final String mimeType;
    private final long contentLength;
    private final Callable<String> refresh;
    private final Http.Guard guard;
    private final String[] headers;

    public HttpAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager, String url, String mimeType) {
        this(trackInfo, sourceManager, url, mimeType, -1, null, null);
    }

    public HttpAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager, String url, String mimeType,
                          long contentLength, Callable<String> refresh, Http.Guard guard, String... headers) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.url = url;
        this.mimeType = mimeType;
        this.contentLength = contentLength > 0 ? contentLength : contentLengthOf(url);
        this.refresh = refresh;
        this.guard = guard;
        this.headers = headers;
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
    public void process(Playback playback) throws Exception {
        long chunk = url.contains(".googlevideo.com/") && contentLength > 0 ? GOOGLEVIDEO_CHUNK : 0;
        HttpStream stream = new HttpStream(url, contentLength, chunk, refresh, headers).guard(guard);
        playback.decode(stream, mimeType);
    }

    public String url() {
        return url;
    }

    public String mimeType() {
        return mimeType;
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new HttpAudioTrack(trackInfo, sourceManager, url, mimeType, contentLength, refresh, guard, headers);
    }
}
