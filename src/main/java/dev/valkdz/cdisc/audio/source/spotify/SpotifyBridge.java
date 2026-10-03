package dev.valkdz.cdisc.audio.source.spotify;

import dev.valkdz.cdisc.audio.source.youtube.YouTubeSearch;
import dev.valkdz.cdisc.util.Json;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpotifyBridge {


    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String TRACK_URL = "https://api.spotify.com/v1/tracks/";
    private static final String SEARCH_URL = "https://api.spotify.com/v1/search?q=";

    private static final Pattern TRACK_IN_TEXT = Pattern.compile("track[/:]([A-Za-z0-9]{22})");
    private static final Pattern BARE_TRACK = Pattern.compile("[A-Za-z0-9]{22}");

    private static final Pattern COLLECTION_IN_TEXT =
            Pattern.compile("(playlist|album)[/:]([A-Za-z0-9]{22})");

    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL);

    private static final String EMBED_URL = "https://open.spotify.com/embed/";
    private static final String OPEN_URL = "https://open.spotify.com/";

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
    private final Map<String, Json> fetched = new ConcurrentHashMap<>();

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

    public Collection readCollection(String collection) throws IOException, InterruptedException {
        if (!blank(backendUrl.get())) {
            try {
                return backendCollection(collection);
            } catch (IOException ignored) {
                // The backend cannot see Spotify's own editorial playlists; the embed page can.
            }
        }
        return embedCollection(collection);
    }

    private Collection backendCollection(String collection)
            throws IOException, InterruptedException {

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        origin(backendUrl.get()) + "/spotify?url=" + URLEncoder.encode(
                                OPEN_URL + collection, StandardCharsets.UTF_8)))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET();

        String password = backendPassword.get();
        if (!blank(password)) request.header("Authorization", "Bearer " + password);

        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("the backend answered " + response.statusCode()
                    + " for " + collection + explain(response.body()));
        }
        return parseBackend(response.body());
    }

    static Collection parseBackend(String json) throws IOException {
        Json body = Json.parse(json);

        List<Entry> entries = new java.util.ArrayList<>();
        for (Json track : body.path("tracks")) {
            String id = track.path("id").asText("");
            if (id.isBlank()) continue;

            entries.add(new Entry(id,
                    track.path("title").asText(""),
                    artistsOf(track.path("artists")),
                    track.path("duration_ms").asLong(0)));
        }

        if (entries.isEmpty()) throw new IOException("the backend listed no tracks");
        return new Collection(body.path("name").asText("Spotify"), entries);
    }

    private static String artistsOf(Json artists) {
        StringBuilder out = new StringBuilder();
        for (Json artist : artists) {
            if (!out.isEmpty()) out.append(", ");
            out.append(artist.asText(""));
        }
        return out.toString();
    }

    private static String explain(String body) {
        try {
            String error = Json.parse(body).path("error").asText("");
            return error.isBlank() ? "" : ": " + error;
        } catch (IOException unreadable) {
            return "";
        }
    }

    // The embed page needs no key, but lists at most the first 100 tracks.
    private Collection embedCollection(String collection)
            throws IOException, InterruptedException {

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

        Json entity = Json.parse(data.group(1))
                .path("props").path("pageProps").path("state").path("data").path("entity");

        List<Entry> entries = new java.util.ArrayList<>();
        for (Json track : entity.path("trackList")) {
            String uri = track.path("uri").asText("");
            if (!uri.startsWith("spotify:track:")) continue;

            entries.add(new Entry(uri.substring("spotify:track:".length()),
                    track.path("title").asText(""),
                    track.path("subtitle").asText("").replace((char) 0x00A0, ' '),
                    track.path("duration").asLong(0)));
        }
        return new Collection(entity.path("name").asText("Spotify"), entries);
    }

    // What a disc needs is known before any YouTube search; the track is kept for the match.
    public Entry entry(String trackId) throws IOException, InterruptedException {
        Match known = matches.get(trackId);
        if (known != null) return new Entry(trackId, known.title(), known.artist(), known.durationMs());

        Json track = hasCredentials() ? spotifyTrack(trackId) : backendTrack(trackId);
        fetched.put(trackId, track);
        Wanted wanted = wantedOf(track);
        return new Entry(trackId, wanted.title(), wanted.artist(), wanted.durationMs());
    }

    public Match resolve(String trackId) throws IOException, InterruptedException {
        Match cached = matches.get(trackId);
        if (cached != null) return cached;

        Json track = fetched.remove(trackId);
        if (track == null) track = hasCredentials() ? spotifyTrack(trackId) : backendTrack(trackId);
        Match found = matchOnYouTube(trackId, track);
        matches.put(trackId, found);
        return found;
    }

    record Wanted(String isrc, String title, String artist, long durationMs) {
    }

    static Wanted wantedOf(Json track) {
        Json artists = track.path("artists");
        String isrc = track.path("external_ids").path("isrc").asText("");

        return new Wanted(isrc.isBlank() ? null : isrc,
                track.path("name").asText(""),
                artists.isArray() && !artists.isEmpty() ? artists.get(0).path("name").asText("") : "",
                track.path("duration_ms").asLong(0));
    }

    private Match matchOnYouTube(String trackId, Json track)
            throws IOException, InterruptedException {

        Wanted wanted = wantedOf(track);
        String byName = wanted.artist().isBlank()
                ? wanted.title() : wanted.artist() + " - " + wanted.title();

        CompletableFuture<String> isrcSearch = wanted.isrc() == null
                ? CompletableFuture.completedFuture(null) : bestVideo(wanted.isrc(), wanted);
        CompletableFuture<String> nameSearch = bestVideo(byName, wanted);

        IOException failure = null;
        String videoId = null;
        String matchedBy = "isrc";

        try {
            try {
                videoId = isrcSearch.get();
            } catch (ExecutionException e) {
                failure = causeOf(e);
            }

            if (videoId == null) {
                matchedBy = "title";
                try {
                    videoId = nameSearch.get();
                } catch (ExecutionException e) {
                    failure = causeOf(e);
                }
            }
        } finally {
            isrcSearch.cancel(true);
            nameSearch.cancel(true);
        }

        if (videoId != null) {
            return new Match(trackId, wanted.isrc(), wanted.title(), wanted.artist(),
                    wanted.durationMs(), videoId, matchedBy);
        }
        if (failure != null) throw failure;
        throw new IOException("no YouTube match for " + byName);
    }

    private static IOException causeOf(ExecutionException e) {
        Throwable cause = e.getCause();
        return cause instanceof IOException io ? io
                : new IOException("YouTube search failed: " + cause, cause);
    }

    public boolean canSearch() {
        return hasCredentials();
    }

    public List<Entry> search(String query, int limit) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(SEARCH_URL + URLEncoder.encode(query, StandardCharsets.UTF_8)
                                + "&type=track&limit=" + Math.max(1, Math.min(50, limit))))
                        .header("Authorization", "Bearer " + appToken())
                        .timeout(Duration.ofSeconds(15))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Spotify search answered " + response.statusCode());

        List<Entry> entries = new java.util.ArrayList<>();
        for (Json track : Json.parse(response.body()).path("tracks").path("items")) {
            String id = track.path("id").asText("");
            if (id.isBlank()) continue;
            Wanted wanted = wantedOf(track);
            entries.add(new Entry(id, wanted.title(), wanted.artist(), wanted.durationMs()));
        }
        return entries;
    }

    private Json spotifyTrack(String trackId) throws IOException, InterruptedException {
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
        return Json.parse(response.body());
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

        Json body = Json.parse(response.body());
        appToken = body.path("access_token").asText(null);
        if (appToken == null) throw new IOException("Spotify issued no token");

        appTokenUntil = System.currentTimeMillis()
                + Math.max(60, body.path("expires_in").asLong(3600) - 60) * 1000L;
        return appToken;
    }

    private Json backendTrack(String trackId) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(origin(backendUrl.get()) + "/spotify?beta=true&url="
                                + URLEncoder.encode(OPEN_URL + "track/" + trackId, StandardCharsets.UTF_8)))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET();

        String password = backendPassword.get();
        if (!blank(password)) request.header("Authorization", "Bearer " + password);

        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("the backend answered " + response.statusCode()
                    + " for Spotify track " + trackId + explain(response.body()));
        }
        return Json.parse(response.body());
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

    private CompletableFuture<String> bestVideo(String query, Wanted wanted) {
        return search.searchAsync(query, visitorData.get(), 6)
                .thenApply(results -> pick(results, wanted));
    }

    static String pick(List<YouTubeSearch.Result> results, Wanted wanted) {
        long seconds = wanted.durationMs() / 1000;
        YouTubeSearch.Result best = null;
        long bestDrift = Long.MAX_VALUE;

        for (YouTubeSearch.Result result : results) {
            if (result.durationSeconds() <= 0) continue;

            if (!plausible(result, wanted.title(), wanted.artist())) continue;

            long drift = Math.abs(result.durationSeconds() - seconds);
            if (seconds > 0 && drift > DURATION_SLACK_SECONDS) continue;

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

    // The artist alone proves nothing: any of their songs within the length slack would pass,
    // and an ISRC search answers with the artist's other uploads, Topic ones included.
    private static boolean plausible(YouTubeSearch.Result result, String title, String artist) {
        String videoTitle = normalise(result.title());
        String wanted = normalise(coreOf(title));
        if (wanted.isEmpty() || !videoTitle.contains(wanted)) return false;
        if (wanted.length() > 3) return true;

        String performer = normalise(artist);
        return result.isArtTrack() || performer.isEmpty() || videoTitle.contains(performer)
                || normalise(result.channel()).contains(performer);
    }

    private static String coreOf(String title) {
        if (title == null) return "";
        int cut = title.length();
        for (String mark : new String[]{" (", " [", " - "}) {
            int at = title.indexOf(mark);
            if (at > 0) cut = Math.min(cut, at);
        }
        return title.substring(0, cut);
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
