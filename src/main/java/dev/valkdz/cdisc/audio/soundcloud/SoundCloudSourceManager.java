package dev.valkdz.cdisc.audio.soundcloud;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SoundCloudSourceManager implements AudioSourceManager {

    private static final String API = "https://api-v2.soundcloud.com";
    private static final int SEARCH_LIMIT = 10;
    private static final int IDS_PER_REQUEST = 50;

    private static final Pattern SCRIPT = Pattern.compile("https://[A-Za-z0-9-.]+/assets/[a-f0-9-]+\\.js");
    private static final Pattern CLIENT_ID = Pattern.compile("[^_]client_id:\"([a-zA-Z0-9-_]+)\"");
    private static final Pattern SEARCH = Pattern.compile("^scsearch(?:\\[([0-9]{1,9}),([0-9]{1,9})])?:\\s*(.*)\\s*$");
    private static final Pattern LINK = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.)?soundcloud\\.com/([a-zA-Z0-9-_:]+)(/[^?#]*)?(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_LINK = Pattern.compile(
            "^(?:https?://)?(?:on\\.soundcloud\\.com|soundcloud\\.app\\.goo\\.gl)/[a-zA-Z0-9-_]+/?(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);

    private volatile String clientId;

    @Override
    public String getSourceName() {
        return "soundcloud";
    }

    @Override
    public AudioItem loadItem(String identifier) {
        try {
            Matcher search = SEARCH.matcher(identifier);
            if (search.matches()) {
                int offset = search.group(1) == null ? 0 : Integer.parseInt(search.group(1));
                int limit = search.group(2) == null ? SEARCH_LIMIT : Math.min(200, Integer.parseInt(search.group(2)));
                return search(search.group(3).trim(), offset, limit);
            }

            String url = identifier.trim();
            if (SHORT_LINK.matcher(url).matches()) {
                String target = Http.redirectOf(url.startsWith("http") ? url : "https://" + url);
                if (target == null) throw new LoadException("Unable to resolve the SoundCloud short link");
                url = target;
            }
            Matcher link = LINK.matcher(url);
            if (!link.matches()) return null;
            String canonical = "https://soundcloud.com/" + link.group(1) + (link.group(2) == null ? "" : link.group(2));
            if (canonical.endsWith("/")) canonical = canonical.substring(0, canonical.length() - 1);
            return canonical.endsWith("/likes") ? likes(canonical) : resolve(canonical);
        } catch (IOException e) {
            throw new LoadException("Loading from SoundCloud failed: " + e.getMessage(), e);
        }
    }

    private AudioItem search(String query, int offset, int limit) throws IOException {
        if (query.isEmpty()) return AudioItem.NONE;
        Json found = api("/search/tracks?q=" + encode(query) + "&offset=" + offset + "&limit=" + limit);
        List<AudioTrack> tracks = new ArrayList<>();
        for (Json entry : found.path("collection")) {
            if (isPremium(entry)) continue;
            AudioTrack track = trackOf(entry);
            if (track != null) tracks.add(track);
        }
        return tracks.isEmpty() ? AudioItem.NONE : new AudioPlaylist("Search results for: " + query, tracks, null, true);
    }

    private static boolean isPremium(Json entry) {
        return "SUB_HIGH_TIER".equals(entry.path("monetization_model").asText(""));
    }

    private AudioItem resolve(String url) throws IOException {
        Json json = api("/resolve?url=" + encode(url));
        if (json == null) return AudioItem.NONE;
        String kind = json.path("kind").asText("");
        if ("track".equals(kind)) {
            AudioTrack track = trackOf(json);
            if (track == null) throw new LoadException("This track is not available");
            return track;
        }
        if ("playlist".equals(kind) || "system-playlist".equals(kind)) {
            return new AudioPlaylist(json.path("title").asText("SoundCloud playlist"), playlistTracks(json), null, false);
        }
        return null;
    }

    private AudioItem likes(String url) throws IOException {
        Json user = api("/resolve?url=" + encode(url.substring(0, url.length() - "/likes".length())));
        if (user == null || !"user".equals(user.path("kind").asText(""))) return AudioItem.NONE;
        Json likes = api("/users/" + user.path("id").asText() + "/likes?limit=200&offset=0");
        List<AudioTrack> tracks = new ArrayList<>();
        for (Json entry : likes.path("collection")) {
            AudioTrack track = entry.has("track") ? trackOf(entry.path("track")) : null;
            if (track != null) tracks.add(track);
        }
        return new AudioPlaylist("Liked by " + user.path("username").asText(""), tracks, null, false);
    }

    // A set lists its first few tracks in full and only the ids of the rest.
    private List<AudioTrack> playlistTracks(Json playlist) throws IOException {
        List<String> order = new ArrayList<>();
        Map<String, Json> full = new HashMap<>();
        for (Json entry : playlist.path("tracks")) {
            String id = entry.path("id").asText(null);
            if (id == null) continue;
            order.add(id);
            if (entry.has("media")) full.put(id, entry);
        }
        List<String> missing = order.stream().filter(id -> !full.containsKey(id)).toList();
        for (int from = 0; from < missing.size(); from += IDS_PER_REQUEST) {
            String ids = String.join(",", missing.subList(from, Math.min(missing.size(), from + IDS_PER_REQUEST)));
            Json batch = api("/tracks?ids=" + encode(ids));
            if (batch == null) continue;
            for (Json entry : batch) full.put(entry.path("id").asText(""), entry);
        }
        List<AudioTrack> tracks = new ArrayList<>();
        for (String id : order) {
            Json entry = full.get(id);
            AudioTrack track = entry == null ? null : trackOf(entry);
            if (track != null) tracks.add(track);
        }
        return tracks;
    }

    private AudioTrack trackOf(Json json) {
        if ("BLOCK".equals(json.path("policy").asText(""))) return null;
        Json transcoding = pick(json.path("media").path("transcodings"));
        if (transcoding == null) return null;

        String permalink = json.path("permalink_url").asText(null);
        long length = json.path("full_duration").asLong(json.path("duration").asLong(0));
        String artwork = json.path("artwork_url").asText(null);
        if (artwork != null) artwork = artwork.replace("-large.", "-t500x500.");
        AudioTrackInfo info = new AudioTrackInfo(
                json.path("title").asText("Unknown title"),
                json.path("user").path("username").asText("Unknown artist"),
                length > 0 ? length : AudioTrackInfo.UNKNOWN_LENGTH,
                transcoding.path("url").asText(),
                false,
                permalink,
                artwork,
                json.path("publisher_metadata").path("isrc").asText(null));
        return new SoundCloudAudioTrack(info, this, transcoding.path("format").path("protocol").asText(""),
                transcoding.path("format").path("mime_type").asText(null),
                json.path("track_authorization").asText(null));
    }

    private static Json pick(Json transcodings) {
        Json best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Json transcoding : transcodings) {
            if (transcoding.path("url").asText(null) == null) continue;
            String protocol = transcoding.path("format").path("protocol").asText("");
            String mime = transcoding.path("format").path("mime_type").asText("");
            int rank;
            if ("progressive".equals(protocol) && mime.startsWith("audio/mpeg")) rank = 0;
            else if ("hls".equals(protocol) && mime.startsWith("audio/mpeg")) rank = 1;
            else if ("hls".equals(protocol) && mime.startsWith("audio/ogg")) rank = 2;
            else if ("hls".equals(protocol) && mime.startsWith("audio/mp4")) rank = 3;
            else continue;
            if (transcoding.path("snipped").asBoolean(false)) rank += 10;
            if (rank < bestRank) {
                best = transcoding;
                bestRank = rank;
            }
        }
        return best;
    }

    String streamUrl(String transcodingUrl, String authorization) throws IOException {
        String path = transcodingUrl + (transcodingUrl.contains("?") ? "&" : "?")
                + (authorization == null ? "" : "track_authorization=" + encode(authorization) + "&");
        Json json = get(path);
        String url = json == null ? null : json.path("url").asText(null);
        if (url == null) throw new IOException("SoundCloud gave no stream address");
        return url;
    }

    private Json api(String path) throws IOException {
        return get(API + path + (path.contains("?") ? "&" : "?"));
    }

    private Json get(String urlWithQuery) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<byte[]> response = Http.get(urlWithQuery + "client_id=" + clientId(attempt > 0));
            int status = response.statusCode();
            if (status == 401 || status == 403) continue;
            if (status == 404) return null;
            Http.expectOk(response, null);
            return Json.parse(new String(response.body(), StandardCharsets.UTF_8));
        }
        throw new IOException("SoundCloud refused its own client id");
    }

    private String clientId(boolean renew) throws IOException {
        String known = clientId;
        if (known != null && !renew) return known;
        synchronized (this) {
            if (clientId != null && !clientId.equals(known)) return clientId;
            clientId = scrapeClientId();
            return clientId;
        }
    }

    private static String scrapeClientId() throws IOException {
        String page = Http.text("https://soundcloud.com");
        List<String> scripts = new ArrayList<>();
        Matcher matcher = SCRIPT.matcher(page);
        while (matcher.find()) scripts.add(matcher.group());
        for (int i = scripts.size() - 1; i >= 0; i--) {
            Matcher id = CLIENT_ID.matcher(Http.text(scripts.get(i)));
            if (id.find()) return id.group(1);
        }
        throw new IOException("Could not find SoundCloud's client id");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
