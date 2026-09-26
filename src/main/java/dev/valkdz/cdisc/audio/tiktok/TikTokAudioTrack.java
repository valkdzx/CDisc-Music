package dev.valkdz.cdisc.audio.tiktok;

import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.net.URI;

public final class TikTokAudioTrack extends DelegatedAudioTrack {

    private final TikTokSourceManager sourceManager;

    public TikTokAudioTrack(AudioTrackInfo trackInfo, TikTokSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
    }

    // Media addresses expire within a day, so a queued or restored track resolves afresh on every play.
    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        try (HttpInterface http = sourceManager.getHttpInterface()) {
            TikTokItem item = sourceManager.reader().read(http, trackInfo.identifier);

            try (PersistentHttpStream stream = new PersistentHttpStream(http, URI.create(item.mediaUrl()), null)) {
                processDelegate(item.isMp3()
                        ? new Mp3AudioTrack(trackInfo, stream)
                        : new MpegAudioTrack(trackInfo, stream), executor);
            }
        }
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new TikTokAudioTrack(trackInfo, sourceManager);
    }
}
