package dev.valkdz.cdisc.audio.sabr;

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.lavalink.youtube.track.YoutubeMpegStreamAudioTrack;

import java.net.URI;
import java.util.function.Supplier;

public final class LiveAudioTrack extends DelegatedAudioTrack {

    private final AudioSourceManager sourceManager;
    private final HttpInterfaceManager interfaces;
    private final Supplier<String> url;

    public LiveAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager,
                          HttpInterfaceManager interfaces, Supplier<String> url) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.interfaces = interfaces;
        this.url = url;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        // Asked for on every start, not held in a field: a live URL expires, and a dropped
        // stream is resumed by replaying this same track.
        URI target = URI.create(url.get());

        try (HttpInterface httpInterface = interfaces.getInterface()) {
            processDelegate(new YoutubeMpegStreamAudioTrack(trackInfo, httpInterface, target), executor);
        }
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new LiveAudioTrack(trackInfo, sourceManager, interfaces, url);
    }
}
