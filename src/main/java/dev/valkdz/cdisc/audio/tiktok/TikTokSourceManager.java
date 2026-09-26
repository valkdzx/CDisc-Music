package dev.valkdz.cdisc.audio.tiktok;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;

public final class TikTokSourceManager implements AudioSourceManager, HttpConfigurable {

    private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
    private final TikTokReader reader = new TikTokReader();

    @Override
    public String getSourceName() {
        return "tiktok";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        if (!reader.matches(reference.identifier)) return null;

        try (HttpInterface http = getHttpInterface()) {
            return trackOf(reader.read(http, reference.identifier));
        } catch (TikTokReader.TikTokException e) {
            throw new FriendlyException(e.getMessage(), FriendlyException.Severity.COMMON, e);
        } catch (IOException e) {
            throw new FriendlyException("Could not read the TikTok video", FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    public AudioTrack trackOf(TikTokItem item) {
        return new TikTokAudioTrack(new AudioTrackInfo(
                item.title(),
                item.author() == null ? "TikTok" : item.author(),
                item.durationMs(),
                item.pageUrl(),
                false,
                item.pageUrl(),
                item.artworkUrl(),
                null), this);
    }

    public TikTokReader reader() {
        return reader;
    }

    public HttpInterface getHttpInterface() {
        return httpInterfaceManager.getInterface();
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
        return new TikTokAudioTrack(trackInfo, this);
    }

    @Override
    public void shutdown() {
        try {
            httpInterfaceManager.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        httpInterfaceManager.configureRequests(configurator);
    }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
        httpInterfaceManager.configureBuilder(configurator);
    }
}
