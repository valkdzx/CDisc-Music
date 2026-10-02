package dev.valkdz.cdisc.audio.soundcloud;

import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.HttpStream;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.audio.player.Playback;

public final class SoundCloudProxyTrack extends AudioTrack {

    private static final long FRESH_MS = 5 * 60 * 1000L;

    private final SoundCloudProxySourceManager sourceManager;
    private final String streamUrl;
    private final String mimeType;
    private final long resolvedAt = System.currentTimeMillis();

    SoundCloudProxyTrack(AudioTrackInfo trackInfo, SoundCloudProxySourceManager sourceManager,
                         String streamUrl, String mimeType) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.streamUrl = streamUrl;
        this.mimeType = mimeType;
    }

    @Override
    public void process(Playback playback) throws Exception {
        String url = streamUrl;
        String mime = mimeType;
        // The stream link is signed with an expiry, so a queued or repeated track asks again.
        if (url == null || System.currentTimeMillis() - resolvedAt > FRESH_MS) {
            SoundCloudProxySourceManager.Resolved fresh = sourceManager.resolve(trackInfo.identifier);
            if (fresh == null) throw new LoadException("This SoundCloud link is not a track");
            url = fresh.streamUrl();
            mime = fresh.mimeType();
        }
        playback.decode(new HttpStream(url, -1), mime);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new SoundCloudProxyTrack(trackInfo, sourceManager, streamUrl, mimeType);
    }
}
