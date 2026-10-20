package dev.valkdz.cdisc.feature.lyrics;

import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.IntSupplier;

public final class LrcLibProvider implements LyricsProvider {

    private static final String BASE = "https://lrclib.net/api/";

    private static final int DURATION_TOLERANCE = 8;

    private static final int MAX_CANDIDATES = 12;

    private static final int MIN_LINES = 3;

    private final HttpClient http;
    private final String userAgent;

    private final IntSupplier timeoutSeconds;

    public LrcLibProvider(String userAgent, IntSupplier timeoutSeconds) {
        this.userAgent = userAgent;
        this.timeoutSeconds = timeoutSeconds;
        this.http = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String id() {
        return "lrclib";
    }

    @Override
    public SyncedLyrics fetch(LyricsQuery query) throws Exception {
        SyncedLyrics found = attempt(query);
        if (found != null) return found;

        SyncedLyrics stripped = attempt(query.withoutFeatures());
        if (stripped != null) return stripped;

        return attempt(query.fallback());
    }

    private SyncedLyrics attempt(LyricsQuery query) throws Exception {
        if (query == null || !query.isUsable()) return null;
        if (query.artist().isBlank()) return search(query, "");

        IOException busy = null;
        try {
            SyncedLyrics exact = fetchExact(query);
            if (exact != null) return exact;
        } catch (IOException e) {
            // LRCLIB sheds /get first when overloaded while /search still answers.
            busy = e;
        }
        SyncedLyrics found = search(query, query.primaryArtist());
        if (found == null) found = search(query, "");
        if (found == null && busy != null) throw busy;
        return found;
    }

    private SyncedLyrics fetchExact(LyricsQuery query) throws Exception {
        StringBuilder url = new StringBuilder(BASE).append("get?")
                .append("artist_name=").append(encode(query.artist()))
                .append("&track_name=").append(encode(query.track()));

        int seconds = query.durationSeconds();
        if (seconds > 0) {
            url.append("&duration=").append(seconds);
        }

        Json body = get(url.toString());
        return body == null || !query.titleMatches(body.get("trackName").text()) ? null : synced(body);
    }

    private SyncedLyrics search(LyricsQuery query, String artist) throws Exception {
        String url = BASE + "search?track_name=" + encode(query.track())
                + (artist.isBlank() ? "" : "&artist_name=" + encode(artist));

        Json body = get(url);
        if (body == null) return null;

        SyncedLyrics best = null;
        long bestRank = Long.MAX_VALUE;
        int seen = 0;

        for (Json candidate : body.values()) {
            if (++seen > MAX_CANDIDATES) break;

            long gap = durationGap(query, candidate);
            if (query.durationSeconds() > 0 && gap > DURATION_TOLERANCE) continue;
            long rank = query.rank(candidate.get("trackName").text(), candidate.get("artistName").text(), gap);
            if (rank < 0 || rank >= bestRank) continue;

            SyncedLyrics lyrics = synced(candidate);
            if (lyrics == null) continue;
            bestRank = rank;
            best = lyrics;
        }

        return best;
    }

    private static long durationGap(LyricsQuery query, Json candidate) {
        int wanted = query.durationSeconds();
        if (wanted <= 0) return 0;

        String raw = candidate.get("duration").text();
        if (raw == null) return 0;

        double found;
        try {
            found = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
        if (found <= 0) return 0;

        return Math.abs(Math.round(found) - wanted);
    }

    static SyncedLyrics synced(Json body) {
        if (body.get("instrumental").asBoolean(false)) return null;
        String lrc = body.get("syncedLyrics").text();
        SyncedLyrics lyrics = lrc == null ? null : SyncedLyrics.parse(lrc);
        if (lyrics == null) return null;

        int sung = 0;
        for (SyncedLyrics.Line line : lyrics.lines()) {
            if (!line.isBlank() && ++sung >= MIN_LINES) return lyrics;
        }
        return null;
    }

    private Json get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds.getAsInt())))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 404) return null;
        if (status != 200) {
            throw new IOException("LRCLIB returned HTTP " + status);
        }
        return Json.parse(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
