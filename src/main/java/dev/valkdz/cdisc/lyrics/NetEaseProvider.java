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
import java.util.Locale;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

public final class NetEaseProvider implements LyricsProvider {

    private static final String SEARCH = "https://music.163.com/api/search/get";
    private static final String LYRIC = "https://music.163.com/api/song/lyric";

    private static final int DURATION_TOLERANCE = 8;

    private static final int MAX_CANDIDATES = 8;

    private static final Pattern CREDIT_LINE = Pattern.compile(
            ".*[：].*|.*\\b(?:作词|作曲|编曲|制作人|"
                    + "混音|母带|录音|出品|监制)\\b.*");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";

    private final HttpClient http;
    private final IntSupplier timeoutSeconds;

    public NetEaseProvider(IntSupplier timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.http = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String id() {
        return "netease";
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

        String terms = (query.artist().isBlank() ? "" : query.artist() + " ") + query.track();
        JsonBrowser body = get(SEARCH + "?s=" + encode(terms) + "&type=1&limit=" + MAX_CANDIDATES);
        if (body == null) return null;

        long bestId = -1;
        long bestGap = Long.MAX_VALUE;

        for (JsonBrowser song : body.get("result").get("songs").values()) {
            long id = song.get("id").asLong(-1);
            if (id < 0) continue;

            long gap = durationGap(query, song);
            if (gap < bestGap) {
                bestGap = gap;
                bestId = id;
            }
        }

        if (bestId < 0) return null;

        if (query.durationSeconds() > 0 && bestGap > DURATION_TOLERANCE) return null;

        return lyricsOf(bestId);
    }

    private SyncedLyrics lyricsOf(long songId) throws Exception {
        JsonBrowser body = get(LYRIC + "?id=" + songId + "&lv=1&kv=1&tv=-1");
        if (body == null) return null;

        String lrc = body.get("lrc").get("lyric").text();
        if (lrc == null || lrc.isBlank()) return null;

        SyncedLyrics parsed = SyncedLyrics.parse(lrc);
        return parsed == null ? null : withoutCredits(parsed);
    }

    private static SyncedLyrics withoutCredits(SyncedLyrics lyrics) {
        StringBuilder rebuilt = new StringBuilder();
        int kept = 0;

        for (SyncedLyrics.Line line : lyrics.lines()) {
            if (!line.isBlank() && CREDIT_LINE.matcher(line.text()).matches()) continue;
            if (!line.isBlank()) kept++;

            long totalSeconds = line.timeMs() / 1000L;

            rebuilt.append('[')
                    .append(String.format(Locale.ROOT, "%02d:%02d.%02d",
                            totalSeconds / 60, totalSeconds % 60, (line.timeMs() % 1000L) / 10L))
                    .append(']')
                    .append(line.text())
                    .append('\n');
        }

        return kept == 0 ? null : SyncedLyrics.parse(rebuilt.toString());
    }

    private static long durationGap(LyricsQuery query, JsonBrowser song) {
        int wanted = query.durationSeconds();
        if (wanted <= 0) return 0;

        long found = song.get("duration").asLong(0);
        if (found <= 0) return 0;

        return Math.abs(found / 1000L - wanted);
    }

    private JsonBrowser get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds.getAsInt())))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("NetEase returned HTTP " + response.statusCode());
        }

        JsonBrowser body = JsonBrowser.parse(response.body());

        long code = body.get("code").asLong(200);
        return code == 200 ? body : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
