package dev.valkdz.cdisc.audio.backend;

import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioPlaylist;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
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

    public BackendMusicSourceManager(Service service, String endpoint, boolean fallback, Logger logger) {
        this.service = service;
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.fallback = fallback;
        this.logger = logger;
    }

    @Override
    public String getSourceName() {
        return service.sourceName;
    }

    @Override
    public AudioItem loadItem(String identifier) {
        if (identifier.startsWith(service.searchPrefix)) {
            return search(identifier.substring(service.searchPrefix.length()).trim());
        }
        return service.links.matcher(identifier).matches() ? link(identifier) : null;
    }

    private AudioItem search(String query) {
        if (query.isEmpty()) return AudioItem.NONE;

        Json json = ask("/search?q=" + encode(query) + "&limit=" + SEARCH_LIMIT, true);
        if (json == null) return null;

        List<AudioTrack> tracks = tracksOf(json.get("results"));
        return tracks.isEmpty() ? AudioItem.NONE
                : new AudioPlaylist("Search results for: " + query, tracks, null, true);
    }

    private AudioItem link(String url) {
        Json json = ask("?url=" + encode(url), true);
        if (json == null) return null;

        if (!json.get("tracks").isNull()) {
            List<AudioTrack> tracks = tracksOf(json.get("tracks"));
            if (tracks.isEmpty()) return AudioItem.NONE;
            String name = json.get("name").text();
            return new AudioPlaylist(name == null ? service.label : name, tracks, null, false);
        }

        Stream stream = streamOf(json);
        if (stream == null) throw refused(json, 200);
        return new BackendMusicTrack(infoOf(json, url), this, stream);
    }

    Stream resolve(String url) {
        Json json = ask("?url=" + encode(url), false);
        if (json == null) throw new LoadException(service.label + " does not recognise this link");

        Stream stream = streamOf(json);
        if (stream == null) throw refused(json, 200);
        return stream;
    }

    private List<AudioTrack> tracksOf(Json list) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (Json entry : list.values()) {
            String url = entry.get("url").text();
            if (url == null || url.isBlank() || !entry.get("available").asBoolean(true)) continue;
            tracks.add(new BackendMusicTrack(infoOf(entry, url), this, null));
        }
        return tracks;
    }

    private AudioTrackInfo infoOf(Json json, String asked) {
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

    private static long lengthOf(Json json) {
        long ms = json.get("duration_ms").asLong(0);
        if (ms > 0) return ms;

        String seconds = json.get("duration").text();
        try {
            return seconds == null ? AudioTrackInfo.UNKNOWN_LENGTH : Math.round(Double.parseDouble(seconds) * 1000);
        } catch (NumberFormatException e) {
            return AudioTrackInfo.UNKNOWN_LENGTH;
        }
    }

    private static Stream streamOf(Json json) {
        Json stream = json.get("stream");
        String url = stream.get("url").text();
        if (url == null || url.isBlank()) return null;
        return new Stream(url, stream.get("protocol").text(), stream.get("mime_type").text());
    }

    private Json ask(String path, boolean mayFallBack) {
        int status;
        Json json = null;
        try {
            HttpResponse<byte[]> response = Http.get(endpoint + path);
            status = response.statusCode();
            String body = new String(response.body(), StandardCharsets.UTF_8);
            if (body.trim().startsWith("{")) json = Json.parse(body);
        } catch (IOException e) {
            return unanswered(mayFallBack, "could not be reached (" + e.getMessage() + ")", e);
        }

        if (status == 200 && json != null) return json;
        if (json == null || status >= 500) return unanswered(mayFallBack, "answered HTTP " + status, null);
        if (NOT_OURS.contains(json.get("code").text())) return null;
        throw refused(json, status);
    }

    private Json unanswered(boolean mayFallBack, String what, Throwable cause) {
        if (fallback && mayFallBack) {
            logger.warning("The " + service.label + " backend " + what + "; trying the token instead.");
            return null;
        }
        throw new LoadException("The " + service.label + " backend " + what, cause);
    }

    private LoadException refused(Json json, int status) {
        String code = json.get("code").text();
        if ("not_found".equals(code)) return new LoadException("No such " + service.label + " track");

        String error = json.get("error").text();
        return new LoadException("The " + service.label + " backend refused this track: "
                + (error == null ? "HTTP " + status : error));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
