package dev.valkdz.cdisc.audio.hls;

import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3TrackProvider;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.io.IOException;

public final class HlsVodAudioTrack extends BaseAudioTrack {

    private final AudioSourceManager sourceManager;
    private final HttpInterfaceManager interfaces;
    private final String playlistUrl;

    private volatile long startMs;

    public HlsVodAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager,
                            HttpInterfaceManager interfaces, String playlistUrl) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.interfaces = interfaces;
        this.playlistUrl = playlistUrl;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        try (HttpInterface http = interfaces.getInterface()) {
            HlsPlaylist playlist = HlsPlaylist.fetch(http, playlistUrl);
            executor.executeProcessingLoop(() -> play(executor, http, playlist),
                    position -> startMs = position);
        }
    }

    private void play(LocalAudioTrackExecutor executor, HttpInterface http, HlsPlaylist playlist)
            throws Exception {
        long from = startMs;
        int index = playlist.indexAt(from);

        try (NonSeekableInputStream stream = new NonSeekableInputStream(
                new HlsMp3Stream(http, playlist, index, false))) {
            Mp3TrackProvider provider = new Mp3TrackProvider(executor.getProcessingContext(), stream);
            try {
                provider.parseHeaders();
                // Decoding restarts at a segment boundary; the pipeline drops audio up to the asked position.
                if (from > 0) provider.recordSeek(from, playlist.segments().get(index).startMs());
                provider.provideFrames();
            } finally {
                provider.close();
            }
        }
    }

    public static HlsMp3Stream openWhole(HttpInterfaceManager interfaces, String playlistUrl)
            throws IOException {
        HttpInterface http = interfaces.getInterface();
        try {
            return new HlsMp3Stream(http, HlsPlaylist.fetch(http, playlistUrl), 0, true);
        } catch (IOException | RuntimeException e) {
            http.close();
            throw e;
        }
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new HlsVodAudioTrack(trackInfo, sourceManager, interfaces, playlistUrl);
    }
}
