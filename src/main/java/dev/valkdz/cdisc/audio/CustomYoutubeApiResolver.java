package dev.valkdz.cdisc.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomYoutubeApiResolver {

    private static final Logger LOGGER = Logger.getLogger("CDisc");

    private static final Pattern VIDEO_ID_IN_URL = Pattern.compile(
            "(?:v=|youtu\\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})");
    private static final Pattern BARE_VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern OWN_STREAM_ID = Pattern.compile("/audio/([A-Za-z0-9_-]{11})");

    private final String baseUrl;
    private final HttpAudioSourceManager httpProbe;
    private final HttpClient http;
    private final boolean proxy;

    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cdisc-api-resolver-io");
        t.setDaemon(true);
        return t;
    });

    public CustomYoutubeApiResolver(String baseUrl, HttpAudioSourceManager httpProbe) {
        this(baseUrl, httpProbe, false);
    }

    public CustomYoutubeApiResolver(String baseUrl, HttpAudioSourceManager httpProbe, boolean proxy) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpProbe = httpProbe;
        this.proxy = proxy;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public HttpAudioSourceManager getHttpSourceManager() {
        return httpProbe;
    }

    public void shutdown() {
        ioExecutor.shutdownNow();
    }

    public String streamUrlFor(String videoId) {
        return baseUrl + "/audio/" + videoId + (proxy ? "?proxy=true" : "?redirect=true");
    }

    public String proxyStreamUrlFor(String videoId) {
        return baseUrl + "/audio/" + videoId + "?proxy=true";
    }

    public String extractIdFromOwnStreamUrl(String url) {
        if (url == null || !url.startsWith(baseUrl)) return null;
        Matcher m = OWN_STREAM_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public AudioTrack resolve(AudioPlayerManager playerManager, String identifier) {
        try {
            String videoId = extractVideoId(identifier);
            if (videoId == null) return null;

            String streamUrl = streamUrlFor(videoId);

            CompletableFuture<JsonBrowser> infoFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return getJson("/get-info/" + videoId);
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.WARNING,
                            "[CDisc] custom-api /get-info failed for '" + videoId + "'", e);
                    return null;
                }
            }, ioExecutor);

            CompletableFuture<HttpAudioTrack> streamFuture = CompletableFuture.supplyAsync(
                    () -> probeHttpTrack(playerManager, streamUrl), ioExecutor);

            HttpAudioTrack httpTrack = streamFuture.join();
            if (httpTrack == null) return null;

            JsonBrowser info = infoFuture.join();
            String title = info != null ? info.get("title").text() : null;
            String author = info != null ? info.get("author").text() : null;
            long lengthMs = info != null ? info.get("length_seconds").asLong(0) * 1000L : 0;
            String watchUrl = info != null ? info.get("watch_url").text() : null;
            String artworkUrl = info != null ? info.get("thumbnail_url").text() : null;

            return buildTrack(httpTrack, videoId, title, author, lengthMs, watchUrl, artworkUrl);
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "[CDisc] custom-api resolve failed for '" + identifier + "'", e);
            return null;
        }
    }

    public AudioTrack wrapStream(AudioPlayerManager playerManager, String streamUrl, String videoId,
                                 String title, String author, long lengthMs, String watchUrl, String artworkUrl) {
        HttpAudioTrack httpTrack = probeHttpTrack(playerManager, streamUrl);
        if (httpTrack == null) return null;
        return buildTrack(httpTrack, videoId, title, author, lengthMs, watchUrl, artworkUrl);
    }

    private HttpAudioTrack probeHttpTrack(AudioPlayerManager playerManager, String streamUrl) {
        try {
            AudioItem probed = httpProbe.loadItem(playerManager, new AudioReference(streamUrl, null));

            if (probed instanceof AudioReference ref && ref.identifier != null) {
                probed = httpProbe.loadItem(playerManager, new AudioReference(ref.identifier, null));
            }

            if (!(probed instanceof HttpAudioTrack httpTrack)) {
                LOGGER.warning("[CDisc] custom-api stream probe for '" + streamUrl
                        + "' did not resolve to a playable HTTP track (got: "
                        + (probed == null ? "null" : probed.getClass().getName()) + ")");
                return null;
            }

            return httpTrack;
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "[CDisc] custom-api stream probe failed for '" + streamUrl + "'", e);
            return null;
        }
    }

    private AudioTrack buildTrack(HttpAudioTrack httpTrack, String videoId, String title, String author,
                                  long lengthMs, String watchUrl, String artworkUrl) {
        AudioTrackInfo trackInfo = new AudioTrackInfo(
                title != null ? title : "Unknown",
                author != null ? author : "Unknown",
                lengthMs > 0 ? lengthMs : Long.MAX_VALUE,
                httpTrack.getInfo().identifier,
                false,
                watchUrl != null ? watchUrl : httpTrack.getInfo().uri,
                artworkUrl,
                null
        );

        return new HttpAudioTrack(trackInfo, httpTrack.getContainerTrackFactory(), httpProbe);
    }

    private String extractVideoId(String identifier) throws IOException, InterruptedException {
        if (identifier.startsWith("ytsearch:")) {
            String query = identifier.substring("ytsearch:".length());
            JsonBrowser search = getJson("/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=1");
            if (search == null) return null;

            List<JsonBrowser> results = search.get("results").values();
            if (results.isEmpty()) return null;

            return results.get(0).get("id").text();
        }

        Matcher m = VIDEO_ID_IN_URL.matcher(identifier);
        if (m.find()) return m.group(1);

        if (BARE_VIDEO_ID.matcher(identifier).matches()) return identifier;

        return null;
    }

    private JsonBrowser getJson(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        return JsonBrowser.parse(response.body());
    }
}
