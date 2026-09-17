package dev.valkdz.cdisc.audio.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.valkdz.cdisc.audio.sabr.YouTubeSearch;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpotifyBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String TRACK_URL = "https://api.spotify.com/v1/tracks/";

    private static final Pattern TRACK_IN_TEXT = Pattern.compile("track[/:]([A-Za-z0-9]{22})");
    private static final Pattern BARE_TRACK = Pattern.compile("[A-Za-z0-9]{22}");

    private static final Pattern COLLECTION_IN_TEXT =
            Pattern.compile("(playlist|album)[/:]([A-Za-z0-9]{22})");

    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL);

    private static final String EMBED_URL = "https://open.spotify.com/embed/";

    private static final long DURATION_SLACK_SECONDS = 5;

    public record Match(String trackId, String isrc, String title, String artist,
                        long durationMs, String videoId, String matchedBy) {

        public String youtubeUrl() {
            return "https://www.youtube.com/watch?v=" + videoId;
        }
    }

    public record Entry(String trackId, String title, String artist, long durationMs) {

        public String spotifyUrl() {
            return "https://open.spotify.com/track/" + trackId;
        }
    }

    public record Collection(String name, List<Entry> entries) {
    }

    private final HttpClient http;
    private final YouTubeSearch search;
    private final Supplier<String> clientId;
    private final Supplier<String> clientSecret;
    private final Supplier<String> backendUrl;
    private final Supplier<String> backendPassword;
    private final Supplier<String> visitorData;

    private final Map<String, Match> matches = new ConcurrentHashMap<>();

    private volatile String appToken;
    private volatile long appTokenUntil;

    public SpotifyBridge(HttpClient http, YouTubeSearch search,
                         Supplier<String> clientId, Supplier<String> clientSecret,
                         Supplier<String> backendUrl, Supplier<String> backendPassword,
                         Supplier<String> visitorData) {
        this.http = http;
        this.search = search;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.backendUrl = backendUrl;
        this.backendPassword = backendPassword;
        this.visitorData = visitorData;
    }

    public boolean isUsable() {
        return hasCredentials() || !blank(backendUrl.get());
    }

    private boolean hasCredentials() {
        return !blank(clientId.get()) && !blank(clientSecret.get());
    }

    public static String trackIdOf(String identifier) {
        if (identifier == null) return null;

        String text = identifier.trim();
        if (!text.contains("spotify") && !text.startsWith("sp:")) return null;

        Matcher inText = TRACK_IN_TEXT.matcher(text);
        if (inText.find()) return inText.group(1);

        String tail = text.startsWith("sp:") ? text.substring(3) : null;
        return tail != null && BARE_TRACK.matcher(tail).matches() ? tail : null;
    }

    public static String collectionOf(String identifier) {
        if (identifier == null) return null;

        String text = identifier.trim();
        if (!text.contains("spotify")) return null;

        Matcher matcher = COLLECTION_IN_TEXT.matcher(text);
        return matcher.find() ? matcher.group(1) + "/" + matcher.group(2) : null;
    }

    // The embed page needs no key, but lists at most the first 100 tracks.
    public Collection readCollection(String collection) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(EMBED_URL + collection))
                        .header("User-Agent", "Mozilla/5.0")
                        .timeout(Duration.ofSeconds(15))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Spotify answered " + response.statusCode() + " for " + collection);
        }
        return parseEmbed(response.body());
    }

    static Collection parseEmbed(String html) throws IOException {
        Matcher data = NEXT_DATA.matcher(html);
        if (!data.find()) throw new IOException("the Spotify embed page carried no track list");

        JsonNode entity = MAPPER.readTree(data.group(1))
                .path("props").path("pageProps").path("state").path("data").path("entity");

        List<Entry> entries = new java.util.ArrayList<>();
        for (JsonNode track : entity.path("trackList")) {
            String uri = track.path("uri").asText("");
            if (!uri.startsWith("spotify:track:")) continue;

            entries.add(new Entry(uri.substring("spotify:track:".length()),
                    track.path("title").asText(""),
                    track.path("subtitle").asText("").replace((char) 0x00A0, ' '),
                    track.path("duration").asLong(0)));
        }
        return new Collection(entity.path("name").asText("Spotify"), entries);
    }

    public Match resolve(String trackId) throws IOException, InterruptedException {
        Match cached = matches.get(trackId);
        if (cached != null) return cached;

        Match found = hasCredentials() ? viaSpotifyApi(trackId) : viaBackend(trackId);
        matches.put(trackId, found);
        return found;
    }

    private Match viaSpotifyApi(String trackId) throws IOException, InterruptedException {
        JsonNode track = spotifyTrack(trackId);

        String isrc = track.path("external_ids").path("isrc").asText(null);
        String title = track.path("name").asText("");
        String artist = track.path("artists").isArray() && !track.path("artists").isEmpty()
                ? track.path("artists").get(0).path("name").asText("") : "";
        long durationMs = track.path("duration_ms").asLong(0);

        String videoId = null;
        String matchedBy = null;

        if (isrc != null && !isrc.isBlank()) {
            videoId = bestVideo(isrc, durationMs, title, artist);
            if (videoId != null) matchedBy = "isrc";
        }

        if (videoId == null) {
            videoId = bestVideo(artist + " - " + title, durationMs, title, artist);
            if (videoId != null) matchedBy = "title";
        }

        if (videoId == null) {
            throw new IOException("no YouTube match for " + artist + " - " + title);
        }

        return new Match(trackId, isrc, title, artist, durationMs, videoId, matchedBy);
    }

    private JsonNode spotifyTrack(String trackId) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(TRACK_URL + trackId))
                        .header("Authorization", "Bearer " + appToken())
                        .timeout(Duration.ofSeconds(15))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            throw new IOException("Spotify has no track " + trackId);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Spotify answered " + response.statusCode());
        }
        return MAPPER.readTree(response.body());
    }

    private synchronized String appToken() throws IOException, InterruptedException {
        if (appToken != null && System.currentTimeMillis() < appTokenUntil) return appToken;

        String secret = Base64.getEncoder().encodeToString(
                (clientId.get() + ":" + clientSecret.get()).getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(TOKEN_URL))
                        .header("Authorization", "Basic " + secret)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Spotify would not issue a token: HTTP "
                    + response.statusCode() + " — check the client id and secret in tokens.yml");
        }

        JsonNode body = MAPPER.readTree(response.body());
        appToken = body.path("access_token").asText(null);
        if (appToken == null) throw new IOException("Spotify issued no token");

        appTokenUntil = System.currentTimeMillis()
                + Math.max(60, body.path("expires_in").asLong(3600) - 60) * 1000L;
        return appToken;
    }

    private Match viaBackend(String trackId) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(origin(backendUrl.get()) + "/spotify/"
                                + URLEncoder.encode(trackId, StandardCharsets.UTF_8)))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET();

        String password = backendPassword.get();
        if (!blank(password)) request.header("Authorization", "Bearer " + password);

        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("the backend answered " + response.statusCode()
                    + " for Spotify track " + trackId);
        }

        JsonNode body = MAPPER.readTree(response.body());
        JsonNode spotify = body.path("spotify");
        String videoId = body.path("youtube").path("id").asText(null);

        if (videoId == null || videoId.isBlank()) {
            throw new IOException("the backend matched no video for " + trackId);
        }

        return new Match(trackId,
                spotify.path("isrc").asText(null),
                spotify.path("title").asText(""),
                spotify.path("artists").isArray() && !spotify.path("artists").isEmpty()
                        ? spotify.path("artists").get(0).asText("") : "",
                spotify.path("duration_ms").asLong(0),
                videoId,
                body.path("matched_by").asText("backend"));
    }

    private static String origin(String url) {
        URI parsed = URI.create(url.trim());
        StringBuilder out = new StringBuilder()
                .append(parsed.getScheme() == null ? "https" : parsed.getScheme())
                .append("://")
                .append(parsed.getHost() == null ? url.trim() : parsed.getHost());

        if (parsed.getPort() > 0) out.append(':').append(parsed.getPort());
        return out.toString();
    }

    private String bestVideo(String query, long durationMs, String title, String artist)
            throws IOException, InterruptedException {

        List<YouTubeSearch.Result> results = search.search(query, visitorData.get(), 6);
        if (results.isEmpty()) return null;

        long wanted = durationMs / 1000;
        YouTubeSearch.Result best = null;
        long bestDrift = Long.MAX_VALUE;

        for (YouTubeSearch.Result result : results) {
            if (result.durationSeconds() <= 0) continue;

            if (!plausible(result, title, artist)) continue;

            long drift = Math.abs(result.durationSeconds() - wanted);
            if (wanted > 0 && drift > DURATION_SLACK_SECONDS) continue;

            boolean better = best == null
                    || (result.isArtTrack() && !best.isArtTrack())
                    || (result.isArtTrack() == best.isArtTrack() && drift < bestDrift);

            if (better) {
                best = result;
                bestDrift = drift;
            }
        }

        return best == null ? null : best.videoId();
    }

    private static boolean plausible(YouTubeSearch.Result result, String title, String artist) {
        if (result.isArtTrack()) return true;

        String videoTitle = normalise(result.title());
        String channel = normalise(result.channel());
        String wanted = normalise(title);

        if (!wanted.isEmpty() && !videoTitle.isEmpty()
                && (videoTitle.contains(wanted) || wanted.contains(videoTitle))) {
            return true;
        }

        String performer = normalise(artist);
        return !performer.isEmpty()
                && (videoTitle.contains(performer) || channel.contains(performer));
    }

    private static String normalise(String text) {
        if (text == null) return "";

        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
