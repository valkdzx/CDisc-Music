package dev.valkdz.cdisc.audio.soundcloud;

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.valkdz.cdisc.audio.sabr.DirectAudioTrack;

public final class SoundCloudProxyTrack extends DelegatedAudioTrack {

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
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        String url = streamUrl;
        String mime = mimeType;
        // The stream link is signed with an expiry, so a queued or repeated track asks again.
        if (url == null || System.currentTimeMillis() - resolvedAt > FRESH_MS) {
            SoundCloudProxySourceManager.Resolved fresh = sourceManager.resolve(trackInfo.identifier);
            if (fresh == null) {
                throw new FriendlyException("This SoundCloud link is not a track",
                        FriendlyException.Severity.COMMON, null);
            }
            url = fresh.streamUrl();
            mime = fresh.mimeType();
        }

        processDelegate(new DirectAudioTrack(trackInfo, sourceManager, sourceManager.interfaces(),
                url, mime, -1), executor);
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
