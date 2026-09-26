package dev.valkdz.cdisc.audio.backend;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class BackendMusicSourceManager implements AudioSourceManager {

    private static final int SEARCH_LIMIT = 10;
    private static final Set<String> NOT_OURS = Set.of("bad_link", "bad_url", "not_a_track");

    public enum Service {
        YANDEX("yandex-music-backend", "Yandex Music", "ymsearch:",
                Pattern.compile("^https?://music\\.yandex\\.[a-z]{2,3}/.+", Pattern.CASE_INSENSITIVE)),
        VK("vk-music-backend", "VK Music", "vksearch:",
                Pattern.compile("^https?://(?:www\\.|m\\.)?vk\\.(?:com|ru)/.+", Pattern.CASE_INSENSITIVE));

        private final String sourceName;
        private final String label;
        private final String searchPrefix;
        private final Pattern links;

        Service(String sourceName, String label, String searchPrefix, Pattern links) {
            this.sourceName = sourceName;
            this.label = label;
            this.searchPrefix = searchPrefix;
            this.links = links;
        }
    }

    record Stream(String url, String protocol, String mimeType) {

        boolean hls() {
            return "hls".equals(protocol);
        }
    }

    private final Service service;
    private final String endpoint;
    private final boolean fallback;
    private final Logger logger;
    private final HttpInterfaceManager interfaces = HttpClientTools.createDefaultThreadLocalManager();

    public BackendMusicSourceManager(Service service, String endpoint, boolean fallback, Logger logger) {
        this.service = service;
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.fallback = fallback;
        this.logger = logger;
        interfaces.configureBuilder(dev.valkdz.cdisc.util.NetProxy::apply);
    }

    @Override
    public String getSourceName() {
        return service.sourceName;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        String identifier = reference.identifier;
        if (identifier.startsWith(service.searchPrefix)) {
            return search(identifier.substring(service.searchPrefix.length()).trim());
        }
        return service.links.matcher(identifier).matches() ? link(identifier) : null;
    }

    private AudioItem search(String query) {
        if (query.isEmpty()) return AudioReference.NO_TRACK;

        JsonBrowser json = ask("/search?q=" + encode(query) + "&limit=" + SEARCH_LIMIT, true);
        if (json == null) return null;

        List<AudioTrack> tracks = tracksOf(json.get("results"));
        return tracks.isEmpty() ? AudioReference.NO_TRACK
                : new BasicAudioPlaylist("Search results for: " + query, tracks, null, true);
    }

    private AudioItem link(String url) {
        JsonBrowser json = ask("?url=" + encode(url), true);
        if (json == null) return null;

        if (!json.get("tracks").isNull()) {
            List<AudioTrack> tracks = tracksOf(json.get("tracks"));
            if (tracks.isEmpty()) return AudioReference.NO_TRACK;
            String name = json.get("name").text();
            return new BasicAudioPlaylist(name == null ? service.label : name, tracks, null, false);
        }

        Stream stream = streamOf(json);
        if (stream == null) throw refused(json, 200);
        return new BackendMusicTrack(infoOf(json, url), this, stream);
    }

    Stream resolve(String url) {
        JsonBrowser json = ask("?url=" + encode(url), false);
        if (json == null) {
            throw new FriendlyException(service.label + " does not recognise this link",
                    FriendlyException.Severity.COMMON, null);
        }

        Stream stream = streamOf(json);
        if (stream == null) throw refused(json, 200);
        return stream;
    }

    private List<AudioTrack> tracksOf(JsonBrowser list) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (JsonBrowser entry : list.values()) {
            String url = entry.get("url").text();
            if (url == null || url.isBlank() || !entry.get("available").asBoolean(true)) continue;
            tracks.add(new BackendMusicTrack(infoOf(entry, url), this, null));
        }
        return tracks;
    }

    private AudioTrackInfo infoOf(JsonBrowser json, String asked) {
        String url = json.get("url").text();
        if (url == null || url.isBlank()) url = asked;

        String title = json.get("title").text();
        String artist = json.get("artist").text();
        return new AudioTrackInfo(
                title == null ? "Unknown title" : title,
                artist == null ? "Unknown artist" : artist,
                lengthOf(json), url, false, url,
                json.get("artwork").text(), json.get("isrc").text());
    }

    private static long lengthOf(JsonBrowser json) {
        long ms = json.get("duration_ms").asLong(0);
        if (ms > 0) return ms;

        String seconds = json.get("duration").text();
        try {
            return seconds == null ? Units.DURATION_MS_UNKNOWN : Math.round(Double.parseDouble(seconds) * 1000);
        } catch (NumberFormatException e) {
            return Units.DURATION_MS_UNKNOWN;
        }
    }

    private static Stream streamOf(JsonBrowser json) {
        JsonBrowser stream = json.get("stream");
        String url = stream.get("url").text();
        if (url == null || url.isBlank()) return null;
        return new Stream(url, stream.get("protocol").text(), stream.get("mime_type").text());
    }

    private JsonBrowser ask(String path, boolean mayFallBack) {
        int status;
        JsonBrowser json = null;
        try (HttpInterface http = interfaces.getInterface();
             CloseableHttpResponse response = http.execute(new HttpGet(endpoint + path))) {
            status = response.getStatusLine().getStatusCode();
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (body.trim().startsWith("{")) json = JsonBrowser.parse(body);
        } catch (IOException e) {
            return unanswered(mayFallBack, "could not be reached (" + e.getMessage() + ")", e);
        }

        if (status == 200 && json != null) return json;
        if (json == null || status >= 500) return unanswered(mayFallBack, "answered HTTP " + status, null);
        if (NOT_OURS.contains(json.get("code").text())) return null;
        throw refused(json, status);
    }

    private JsonBrowser unanswered(boolean mayFallBack, String what, Throwable cause) {
        if (fallback && mayFallBack) {
            logger.warning("The " + service.label + " backend " + what + "; trying the token instead.");
            return null;
        }
        throw new FriendlyException("The " + service.label + " backend " + what,
                FriendlyException.Severity.SUSPICIOUS, cause);
    }

    private FriendlyException refused(JsonBrowser json, int status) {
        String code = json.get("code").text();
        if ("not_found".equals(code)) {
            return new FriendlyException("No such " + service.label + " track",
                    FriendlyException.Severity.COMMON, null);
        }

        String error = json.get("error").text();
        return new FriendlyException("The " + service.label + " backend refused this track: "
                + (error == null ? "HTTP " + status : error), FriendlyException.Severity.COMMON, null);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
        return new BackendMusicTrack(trackInfo, this, null);
    }

    @Override
    public void shutdown() {
        try {
            interfaces.close();
        } catch (IOException ignored) {
        }
    }
}
