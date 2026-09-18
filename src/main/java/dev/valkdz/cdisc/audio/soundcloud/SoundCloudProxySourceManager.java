package dev.valkdz.cdisc.audio.soundcloud;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SoundCloudProxySourceManager implements AudioSourceManager {

    private static final Pattern TRACK_URL = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?soundcloud\\.com/(.+)$", Pattern.CASE_INSENSITIVE);

    private final String endpoint;
    private final HttpInterfaceManager interfaces = HttpClientTools.createDefaultThreadLocalManager();

    public SoundCloudProxySourceManager(String endpoint) {
        this.endpoint = endpoint;
    }

    record Resolved(AudioTrackInfo info, String streamUrl, String mimeType) {
    }

    @Override
    public String getSourceName() {
        return "soundcloud-proxy";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        Matcher matcher = TRACK_URL.matcher(reference.identifier);
        if (!matcher.matches()) return null;

        Resolved resolved = resolve("https://soundcloud.com/" + matcher.group(1));
        if (resolved == null) return null;
        return new SoundCloudProxyTrack(resolved.info(), this, resolved.streamUrl(), resolved.mimeType());
    }

    Resolved resolve(String url) {
        JsonBrowser json;
        int status;
        try (HttpInterface http = interfaces.getInterface();
             CloseableHttpResponse response = http.execute(new HttpGet(
                     endpoint + "?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8)))) {
            status = response.getStatusLine().getStatusCode();
            json = JsonBrowser.parse(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new FriendlyException("The SoundCloud proxy could not be reached",
                    FriendlyException.Severity.SUSPICIOUS, e);
        }

        String code = json.get("code").text();
        if ("not_a_track".equals(code)) return null;
        if ("not_found".equals(code)) {
            throw new FriendlyException("No such SoundCloud track", FriendlyException.Severity.COMMON, null);
        }
        if (status != 200 || json.get("stream").isNull()) {
            String error = json.get("error").text();
            throw new FriendlyException("The SoundCloud proxy refused this track: "
                    + (error == null ? "HTTP " + status : error), FriendlyException.Severity.SUSPICIOUS, null);
        }

        JsonBrowser stream = json.get("stream");
        if (!"progressive".equals(stream.get("protocol").text())) {
            throw new FriendlyException("The SoundCloud proxy offered no progressive stream",
                    FriendlyException.Severity.SUSPICIOUS, null);
        }

        String canonical = json.get("url").text();
        if (canonical == null || canonical.isBlank()) canonical = url;
        AudioTrackInfo info = new AudioTrackInfo(
                json.get("title").safeText(),
                json.get("artist").isNull() ? json.get("user").get("username").safeText() : json.get("artist").text(),
                Math.round(json.get("duration").as(Double.class) * 1000),
                canonical, false, canonical,
                json.get("artwork").text(), json.get("isrc").text());
        return new Resolved(info, stream.get("url").text(), stream.get("mime_type").text());
    }

    HttpInterfaceManager interfaces() {
        return interfaces;
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
        return new SoundCloudProxyTrack(trackInfo, this, null, null);
    }

    @Override
    public void shutdown() {
        try {
            interfaces.close();
        } catch (IOException ignored) {
        }
    }
}
