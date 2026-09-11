package dev.valkdz.cdisc.lyrics;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

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

    private final HttpClient http;
    private final String userAgent;

    private final IntSupplier timeoutSeconds;

    public LrcLibProvider(String userAgent, IntSupplier timeoutSeconds) {
        this.userAgent = userAgent;
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpClient.newBuilder()
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

        if (!query.artist().isBlank()) {
            SyncedLyrics exact = fetchExact(query);
            if (exact != null) return exact;
        }
        return search(query);
    }

    private SyncedLyrics fetchExact(LyricsQuery query) throws Exception {
        StringBuilder url = new StringBuilder(BASE).append("get?")
                .append("artist_name=").append(encode(query.artist()))
                .append("&track_name=").append(encode(query.track()));

        int seconds = query.durationSeconds();
        if (seconds > 0) {
            url.append("&duration=").append(seconds);
        }

        JsonBrowser body = get(url.toString());
        return body == null ? null : synced(body);
    }

    private SyncedLyrics search(LyricsQuery query) throws Exception {
        String url = BASE + "search?track_name=" + encode(query.track())
                + (query.artist().isBlank() ? "" : "&artist_name=" + encode(query.artist()));

        JsonBrowser body = get(url);
        if (body == null) return null;

        JsonBrowser best = null;
        long bestGap = Long.MAX_VALUE;
        int seen = 0;

        for (JsonBrowser candidate : body.values()) {
            if (++seen > MAX_CANDIDATES) break;
            if (candidate.get("syncedLyrics").isNull()) continue;
            if (candidate.get("instrumental").asBoolean(false)) continue;

            long gap = durationGap(query, candidate);
            if (gap < bestGap) {
                bestGap = gap;
                best = candidate;
            }
        }

        if (best == null) return null;

        if (query.durationSeconds() > 0 && bestGap > DURATION_TOLERANCE) return null;

        return synced(best);
    }

    private static long durationGap(LyricsQuery query, JsonBrowser candidate) {
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

    private static SyncedLyrics synced(JsonBrowser body) {
        if (body.get("instrumental").asBoolean(false)) return null;
        String lrc = body.get("syncedLyrics").text();
        return lrc == null ? null : SyncedLyrics.parse(lrc);
    }

    private JsonBrowser get(String url) throws Exception {
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
        return JsonBrowser.parse(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
