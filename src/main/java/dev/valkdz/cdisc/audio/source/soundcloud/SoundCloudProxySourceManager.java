package dev.valkdz.cdisc.audio.source.soundcloud;

import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SoundCloudProxySourceManager implements AudioSourceManager {

    private static final Pattern TRACK_URL = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?soundcloud\\.com/(.+)$", Pattern.CASE_INSENSITIVE);

    private final String endpoint;

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
    public AudioItem loadItem(String identifier) {
        Matcher matcher = TRACK_URL.matcher(identifier);
        if (!matcher.matches()) return null;

        Resolved resolved = resolve("https://soundcloud.com/" + matcher.group(1));
        if (resolved == null) return null;
        return new SoundCloudProxyTrack(resolved.info(), this, resolved.streamUrl(), resolved.mimeType());
    }

    Resolved resolve(String url) {
        Json json;
        int status;
        try {
            HttpResponse<byte[]> response = Http.get(endpoint + "?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8));
            status = response.statusCode();
            json = Json.parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LoadException("The SoundCloud proxy could not be reached", e);
        }

        String code = json.get("code").text();
        if ("not_a_track".equals(code)) return null;
        if ("not_found".equals(code)) throw new LoadException("No such SoundCloud track");
        if (status != 200 || json.get("stream").isNull()) {
            String error = json.get("error").text();
            throw new LoadException("The SoundCloud proxy refused this track: "
                    + (error == null ? "HTTP " + status : error));
        }

        Json stream = json.get("stream");
        if (!"progressive".equals(stream.get("protocol").text())) {
            throw new LoadException("The SoundCloud proxy offered no progressive stream");
        }

        String canonical = json.get("url").text();
        if (canonical == null || canonical.isBlank()) canonical = url;
        AudioTrackInfo info = new AudioTrackInfo(
                json.get("title").safeText(),
                json.get("artist").isNull() ? json.get("user").get("username").safeText() : json.get("artist").text(),
                Math.round(json.get("duration").asDouble(0) * 1000),
                canonical, false, canonical,
                json.get("artwork").text(), json.get("isrc").text());
        return new Resolved(info, stream.get("url").text(), stream.get("mime_type").text());
    }
}
