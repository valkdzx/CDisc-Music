package dev.valkdz.cdisc.audio.source.youtube;

import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YouTubePlaylist {

    private static final String API = "https://www.youtube.com/youtubei/v1/";
    private static final int MAX_ENTRIES = 600;
    private static final int MAX_PAGES = 12;

    public record Entry(String videoId, String title, String channel, long durationSeconds) {}

    public record Contents(String name, List<Entry> entries) {}

    private final String clientVersion;

    public YouTubePlaylist(String clientVersion) {
        this.clientVersion = clientVersion == null || clientVersion.isBlank()
                ? "2.20260828.01.00" : clientVersion;
    }

    // Mixes cannot be browsed; the watch page's queue is the only list of them.
    public Contents read(String listId, String videoId, String visitorData) throws IOException {
        Map<String, Entry> found = new LinkedHashMap<>();
        Json first = call("browse", body(visitorData).put("browseId", "VL" + listId));
        String name = nameOf(first);
        collect(first, found);

        String continuation = continuationOf(first);
        for (int page = 0; continuation != null && page < MAX_PAGES && found.size() < MAX_ENTRIES; page++) {
            Json next = call("browse", body(visitorData).put("continuation", continuation));
            collect(next, found);
            continuation = continuationOf(next);
        }

        if (found.isEmpty()) {
            Json body = body(visitorData).put("playlistId", listId);
            if (videoId != null) body.put("videoId", videoId);
            Json watch = call("next", body);
            Json playlist = watch.path("contents").path("twoColumnWatchNextResults").path("playlist").path("playlist");
            collect(playlist, found);
            name = playlist.path("title").asText(name);
        }
        return new Contents(name == null ? "YouTube playlist" : name, new ArrayList<>(found.values()));
    }

    private Json body(String visitorData) {
        Json client = Json.object().put("clientName", "WEB").put("clientVersion", clientVersion)
                .put("hl", "en").put("gl", "US");
        if (visitorData != null && !visitorData.isBlank()) client.put("visitorData", visitorData);
        return Json.object().set("context", Json.object().set("client", client));
    }

    private Json call(String endpoint, Json body) throws IOException {
        HttpResponse<String> response = Http.post(API + endpoint, body.toString(),
                "Content-Type", "application/json", "X-Youtube-Client-Name", "1",
                "X-Youtube-Client-Version", clientVersion);
        if (response.statusCode() != 200) throw new IOException("YouTube answered HTTP " + response.statusCode());
        return Json.parse(response.body());
    }

    private static String nameOf(Json page) {
        String title = page.path("metadata").path("playlistMetadataRenderer").path("title").asText(null);
        if (title != null) return title;
        Json header = page.path("header");
        title = header.path("playlistHeaderRenderer").path("title").path("simpleText").asText(null);
        if (title != null) return title;
        return header.path("pageHeaderRenderer").path("pageTitle").asText(null);
    }

    private static void collect(Json node, Map<String, Entry> into) {
        if (into.size() >= MAX_ENTRIES) return;
        if (node.isObject()) {
            for (String key : new String[]{"playlistVideoRenderer", "playlistPanelVideoRenderer"}) {
                Json video = node.path(key);
                String id = video.path("videoId").asText(null);
                if (id != null && video.path("isPlayable").asBoolean(true)) {
                    into.putIfAbsent(id, new Entry(id, textOf(video.path("title")),
                            textOf(video.has("shortBylineText") ? video.path("shortBylineText")
                                    : video.path("longBylineText")),
                            seconds(video)));
                }
            }
            for (Json child : node) collect(child, into);
        } else if (node.isArray()) {
            for (Json child : node) collect(child, into);
        }
    }

    private static String continuationOf(Json node) {
        if (node.isObject()) {
            String token = node.path("continuationItemRenderer").path("continuationEndpoint")
                    .path("continuationCommand").path("token").asText(null);
            if (token != null) return token;
            for (Json child : node) {
                String found = continuationOf(child);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (Json child : node) {
                String found = continuationOf(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String textOf(Json node) {
        Json runs = node.path("runs");
        if (runs.isArray() && !runs.isEmpty()) return runs.get(0).path("text").asText(null);
        return node.path("simpleText").asText(null);
    }

    private static long seconds(Json video) {
        long seconds = video.path("lengthSeconds").asLong(0);
        if (seconds > 0) return seconds;
        String label = textOf(video.path("lengthText"));
        if (label == null) return 0;
        long total = 0;
        for (String part : label.split(":")) {
            try {
                total = total * 60 + Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return total;
    }
}
