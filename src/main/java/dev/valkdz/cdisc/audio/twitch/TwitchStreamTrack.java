package dev.valkdz.cdisc.audio.twitch;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.playlists.ExtendedM3uParser;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.ChainedInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

public final class TwitchStreamTrack extends DelegatedAudioTrack {

    private final TwitchStreamAudioSourceManager sourceManager;
    private final String channelName;

    public TwitchStreamTrack(AudioTrackInfo trackInfo, TwitchStreamAudioSourceManager sourceManager,
                             String channelName) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.channelName = channelName;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        TwitchSegments segments = new TwitchSegments(channelName, sourceManager);

        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            String initUrl = initSegmentUrl(httpInterface, segments.playlistUrl(httpInterface));

            if (initUrl != null) {
                // Twitch streams CMAF: the moov is in the EXT-X-MAP init segment and the
                // fragments after it carry none, so playback has to begin with those bytes.
                InputStream joined = new SequenceInputStream(
                        new ByteArrayInputStream(bytes(httpInterface, initUrl)),
                        new ChainedInputStream(() -> segments.getNextSegmentStream(httpInterface)));

                processDelegate(new MpegAudioTrack(trackInfo, new NonSeekableInputStream(joined)), executor);
                return;
            }
        }

        processDelegate(new TwitchStreamAudioTrack(trackInfo, sourceManager), executor);
    }

    private String initSegmentUrl(HttpInterface httpInterface, String playlistUrl) throws IOException {
        if (playlistUrl == null) return null;

        for (String line : text(httpInterface, playlistUrl).split("\n")) {
            ExtendedM3uParser.Line parsed = ExtendedM3uParser.parseLine(line);
            if (parsed.isDirective() && "EXT-X-MAP".equals(parsed.directiveName)) {
                return parsed.directiveArguments.get("URI");
            }
        }
        return null;
    }

    private String text(HttpInterface httpInterface, String url) throws IOException {
        try (CloseableHttpResponse response = httpInterface.execute(sourceManager.createGetRequest(url))) {
            HttpClientTools.assertSuccessWithContent(response, "stream segment playlist");
            return EntityUtils.toString(response.getEntity());
        }
    }

    private byte[] bytes(HttpInterface httpInterface, String url) throws IOException {
        try (CloseableHttpResponse response = httpInterface.execute(sourceManager.createGetRequest(url))) {
            HttpClientTools.assertSuccessWithContent(response, "stream init segment");
            return EntityUtils.toByteArray(response.getEntity());
        }
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new TwitchStreamTrack(trackInfo, sourceManager, channelName);
    }
}
