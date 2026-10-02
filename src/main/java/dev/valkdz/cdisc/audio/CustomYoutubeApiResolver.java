package dev.valkdz.cdisc.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainer;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioTrack;
import dev.valkdz.cdisc.util.Json;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
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
    private final HttpClient noRedirects;
    private final boolean proxy;

    private volatile Consumer<Info> observer;

    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "cdisc-api-resolver-io");
        t.setDaemon(true);
        return t;
    });

    // Separate from ioExecutor: a container probe blocks there, and the client still
    // needs a thread of its own to hand the response over.
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "cdisc-api-resolver-http");
        t.setDaemon(true);
        return t;
    });

    public CustomYoutubeApiResolver(String baseUrl, HttpAudioSourceManager httpProbe) {
        this(baseUrl, httpProbe, false);
    }

    public CustomYoutubeApiResolver(String baseUrl, HttpAudioSourceManager httpProbe, boolean proxy) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpProbe = httpProbe;
        httpProbe.configureBuilder(dev.valkdz.cdisc.util.NetProxy::apply);
        this.proxy = proxy;
        this.http = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(4))
                .executor(httpExecutor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.noRedirects = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(4))
                .executor(httpExecutor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record Info(String videoId, String title, String author, String contentType,
                       long lengthMs, String watchUrl, String artworkUrl, boolean live,
                       boolean playable, String reason, Set<String> countries) {

        public Boolean allowedIn(String region) {
            if (region == null || countries == null || countries.isEmpty()) return null;
            return countries.contains(region.toUpperCase(Locale.ROOT));
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public HttpAudioSourceManager getHttpSourceManager() {
        return httpProbe;
    }

    public void shutdown() {
        ioExecutor.shutdownNow();
        httpExecutor.shutdownNow();
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

    public void setObserver(Consumer<Info> observer) {
        this.observer = observer;
    }

    public record Hit(String videoId, String title, String author, long lengthMs) {}

    public CompletableFuture<List<Hit>> searchAsync(String query, int limit) {
        return getJson("/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + limit)
                .thenApply(json -> {
                    List<Hit> hits = new ArrayList<>();
                    if (json == null) return hits;

                    for (Json found : json.get("results").values()) {
                        String videoId = found.get("id").text();
                        if (videoId == null) continue;

                        hits.add(new Hit(videoId,
                                found.get("title").text(),
                                found.get("channel").text(),
                                found.get("duration").asLong(0) * 1000L));
                    }
                    return hits;
                })
                .exceptionally(e -> {
                    LOGGER.log(Level.WARNING, "[CDisc] custom-api search failed for " + query, e);
                    return List.of();
                });
    }

    public CompletableFuture<Info> infoAsync(String videoId) {
        return getJson("/get-info/" + videoId)
                .thenApply(json -> {
                    if (json == null) return null;
                    Info info = parseInfo(videoId, json);
                    Consumer<Info> watcher = observer;
                    if (watcher != null) watcher.accept(info);
                    return info;
                })
                .exceptionally(e -> {
                    LOGGER.log(Level.WARNING, "[CDisc] custom-api get-info failed for "
                            + videoId, e);
                    return null;
                });
    }

    public AudioTrack resolve(AudioPlayerManager playerManager, String identifier) {
        try {
            return resolveAsync(playerManager, identifier).join();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CDisc] custom-api resolve failed for " + identifier, e);
            return null;
        }
    }

    public CompletableFuture<AudioTrack> resolveAsync(AudioPlayerManager playerManager,
                                                      String identifier) {
        return videoIdAsync(identifier).thenCompose(videoId -> {
            if (videoId == null) return CompletableFuture.completedFuture(null);

            CompletableFuture<Info> info = infoAsync(videoId);
            CompletableFuture<String> location = locationAsync(streamUrlFor(videoId));

            return info.thenCombineAsync(location,
                    (read, target) -> trackFor(playerManager, videoId, read, target), ioExecutor);
        });
    }

    public AudioTrack trackFor(AudioPlayerManager playerManager, String videoId, Info info) {
        return trackFor(playerManager, videoId, info, locationAsync(streamUrlFor(videoId)).join());
    }

    public AudioTrack trackFor(AudioPlayerManager playerManager, String videoId, Info info,
                               String location) {
        String streamUrl = location != null ? location : streamUrlFor(videoId);
        MediaContainerDescriptor container = containerOf(info == null ? null : info.contentType());

        if (container == null || location == null) {
            HttpAudioTrack probed = probeHttpTrack(playerManager, streamUrlFor(videoId));
            if (probed == null) return null;
            container = probed.getContainerTrackFactory();
            streamUrl = probed.getInfo().identifier;
        }

        AudioTrackInfo built = trackInfo(videoId, streamUrl, info);
        long length = dev.valkdz.cdisc.audio.backend.RangedHttpAudioTrack.contentLengthOf(streamUrl);
        return length > 0
                ? new dev.valkdz.cdisc.audio.backend.RangedHttpAudioTrack(built, container, httpProbe, length)
                : new HttpAudioTrack(built, container, httpProbe);
    }

    // The stream URL 302s to googlevideo and lavaplayer refuses redirects while playing,
    // so the target has to be known before the track is built.
    private CompletableFuture<String> locationAsync(String streamUrl) {
        if (proxy) return CompletableFuture.completedFuture(streamUrl);

        HttpRequest request = HttpRequest.newBuilder(URI.create(streamUrl))
                .timeout(Duration.ofSeconds(6))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        return noRedirects.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> response.statusCode() / 100 == 3
                        ? response.headers().firstValue("location").orElse(null)
                        : (response.statusCode() == 200 ? streamUrl : null))
                .exceptionally(ignored -> null);
    }

    private AudioTrackInfo trackInfo(String videoId, String streamUrl, Info info) {
        boolean live = info != null && info.live();
        long length = info == null || info.lengthMs() <= 0 || live
                ? Units.DURATION_MS_UNKNOWN : info.lengthMs();

        return new AudioTrackInfo(
                info != null && info.title() != null ? info.title() : "Unknown",
                info != null && info.author() != null ? info.author() : "Unknown",
                length,
                streamUrl,
                live,
                info != null && info.watchUrl() != null
                        ? info.watchUrl() : "https://www.youtube.com/watch?v=" + videoId,
                info == null ? null : info.artworkUrl(),
                null);
    }

    private static MediaContainerDescriptor containerOf(String contentType) {
        if (contentType == null || contentType.isBlank()) return null;

        String type = contentType.toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon > 0) type = type.substring(0, semicolon).trim();

        MediaContainer container = switch (type) {
            case "audio/webm", "video/webm", "audio/x-matroska", "video/x-matroska" -> MediaContainer.MKV;
            case "audio/mp4", "video/mp4", "audio/m4a", "audio/x-m4a" -> MediaContainer.MP4;
            case "audio/mpeg", "audio/mp3" -> MediaContainer.MP3;
            case "audio/ogg", "application/ogg" -> MediaContainer.OGG;
            case "audio/wav", "audio/x-wav", "audio/wave" -> MediaContainer.WAV;
            case "audio/flac", "audio/x-flac" -> MediaContainer.FLAC;
            case "audio/aac", "audio/aacp" -> MediaContainer.ADTS;
            default -> null;
        };

        return container == null ? null : new MediaContainerDescriptor(container.probe, null);
    }

    private HttpAudioTrack probeHttpTrack(AudioPlayerManager playerManager, String streamUrl) {
        try {
            AudioItem probed = httpProbe.loadItem(playerManager, new AudioReference(streamUrl, null));

            if (probed instanceof AudioReference ref && ref.identifier != null) {
                probed = httpProbe.loadItem(playerManager, new AudioReference(ref.identifier, null));
            }

            if (!(probed instanceof HttpAudioTrack httpTrack)) {
                LOGGER.warning("[CDisc] custom-api stream probe for " + streamUrl
                        + " did not resolve to a playable HTTP track (got: "
                        + (probed == null ? "null" : probed.getClass().getName()) + ")");
                return null;
            }

            return httpTrack;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CDisc] custom-api stream probe failed for " + streamUrl, e);
            return null;
        }
    }

    private static Info parseInfo(String videoId, Json json) {
        Json audio = json.get("audio");
        String contentType = audio.get("content_type").text();
        if (contentType == null) contentType = json.get("content_type").text();

        Json playability = json.get("playability");
        String status = playability.get("status").text();

        Set<String> countries = new HashSet<>();
        for (Json country : json.get("available_countries").values()) {
            String code = country.text();
            if (code != null) countries.add(code.toUpperCase(Locale.ROOT));
        }

        return new Info(
                videoId,
                json.get("title").text(),
                json.get("author").text(),
                contentType,
                json.get("length_seconds").asLong(0) * 1000L,
                json.get("watch_url").text(),
                json.get("thumbnail_url").text(),
                json.get("is_live").asBoolean(false),
                status == null || "OK".equals(status),
                playability.get("reason").text(),
                countries);
    }

    public String extractVideoId(String identifier) {
        Matcher m = VIDEO_ID_IN_URL.matcher(identifier);
        if (m.find()) return m.group(1);

        return BARE_VIDEO_ID.matcher(identifier).matches() ? identifier : null;
    }

    private CompletableFuture<String> videoIdAsync(String identifier) {
        if (!identifier.startsWith("ytsearch:")) {
            return CompletableFuture.completedFuture(extractVideoId(identifier));
        }

        String query = identifier.substring("ytsearch:".length());
        return getJson("/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=1")
                .thenApply(search -> {
                    if (search == null) return null;
                    List<Json> results = search.get("results").values();
                    return results.isEmpty() ? null : results.get(0).get("id").text();
                });
    }

    private CompletableFuture<Json> getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) return null;
                    try {
                        return Json.parse(response.body());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }
}
