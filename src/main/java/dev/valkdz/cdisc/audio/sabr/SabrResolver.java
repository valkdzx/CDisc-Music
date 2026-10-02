package dev.valkdz.cdisc.audio.sabr;

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import dev.lavalink.youtube.cipher.CipherManager;
import dev.lavalink.youtube.cipher.LocalSignatureCipherManager;
import dev.lavalink.youtube.cipher.RemoteCipherManager;
import dev.lavalink.youtube.track.format.StreamFormat;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SabrResolver {

    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private static final Pattern BARE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private final HttpClient http;
    private final boolean sabr;
    public static final String VISION_SOURCE = "youtube-visionos";
    public static final String EMBEDDED_SOURCE = "youtube-embedded";

    private static final int SESSION_ATTEMPTS = 3;

    public enum Plan {
        VISION_THEN_EMBED("visionos"), VISION_ONLY("visionos-only"), EMBED_ONLY("embedded");

        private final String key;

        Plan(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Plan of(String key) {
            for (Plan plan : values()) {
                if (plan.key.equals(key)) return plan;
            }
            return null;
        }
    }

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();

    private final SabrSourceManager sourceManager = new SabrSourceManager();
    private final SabrSourceManager visionSource = new SabrSourceManager(VISION_SOURCE);
    private final SabrSourceManager embeddedSource = new SabrSourceManager(EMBEDDED_SOURCE);
    private final YoutubeCipher ownCipher;
    private final EmbeddedPlayer embedded;
    private final Supplier<InnerTubePlayer.ClientIdentity> identity;

    private final CipherManager cipher;

    private static final long VISITOR_DATA_TTL_MS = 60 * 60 * 1000L;

    private String mintedVisitorData;
    private long mintedVisitorDataUntil;
    private volatile String verifiedVisitor;
    private volatile String region;
    private final HttpInterfaceManager cipherInterfaces = HttpClientTools.createDefaultThreadLocalManager();

    public SabrResolver(Supplier<InnerTubePlayer.ClientIdentity> identity, String remoteCipherUrl,
                        boolean sabr) {
        this.identity = identity;
        this.sabr = sabr;
        cipherInterfaces.configureBuilder(dev.valkdz.cdisc.util.NetProxy::apply);
        this.cipher = isBlank(remoteCipherUrl)
                ? new LocalSignatureCipherManager()
                : new RemoteCipherManager(remoteCipherUrl);
        this.http = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(10))

                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HttpClient pages = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.ownCipher = new YoutubeCipher(pages);
        this.embedded = new EmbeddedPlayer(pages, ownCipher);
    }

    public static String videoIdOf(String identifier) {
        if (identifier == null) return null;
        if (BARE_ID.matcher(identifier).matches()) return identifier;

        Matcher matcher = VIDEO_ID.matcher(identifier);
        return matcher.find() ? matcher.group(1) : null;
    }

    public AudioTrack resolve(String identifier, String discTitle, String discAuthor)
            throws IOException {
        return resolve(identifier, discTitle, discAuthor, false);
    }

    public AudioTrack resolve(String identifier, String discTitle, String discAuthor,
                              boolean namesWin) throws IOException {
        return resolve(identifier, discTitle, discAuthor, namesWin, null);
    }

    public String planOf(String videoId) {
        Plan plan = videoId == null ? null : plans.get(videoId);
        return plan == null ? null : plan.key();
    }

    public AudioTrack resolve(String identifier, String discTitle, String discAuthor,
                              boolean namesWin, String planKey) throws IOException {
        String videoId = videoIdOf(identifier);
        if (videoId == null) return null;

        Plan plan = Plan.of(planKey);
        if (plan == Plan.EMBED_ONLY) {
            AudioTrack fromEmbed = resolveEmbedded(videoId, discTitle, discAuthor, namesWin);
            if (fromEmbed != null) plans.put(videoId, Plan.EMBED_ONLY);
            return fromEmbed;
        }

        PlaybackRefused refused = null;
        AudioTrack direct;
        try {
            direct = resolveDirect(videoId, discTitle, discAuthor, namesWin);
        } catch (PlaybackRefused e) {
            refused = e;
            direct = null;
        }

        if (direct != null) return direct;

        if (plan != Plan.VISION_ONLY && (refused == null || !refused.regional())) {
            try {
                AudioTrack fromEmbed = resolveEmbedded(videoId, discTitle, discAuthor, namesWin);
                if (fromEmbed != null) {
                    plans.put(videoId, refused != null ? Plan.EMBED_ONLY : Plan.VISION_THEN_EMBED);
                    return fromEmbed;
                }
            } catch (PlaybackRefused e) {
                if (refused == null) refused = e;
            } catch (Exception ignored) {
            }
        }

        if (!sabr) {
            if (refused != null) throw refused;
            return null;
        }

        InnerTubePlayer.PlayerResponse response = new InnerTubePlayer(http, identity.get()).fetch(videoId);

        if (!response.isPlayable()) {
            // The WEB client can be refused where VISIONOS was not, so the earlier
            // verdict is the one worth reporting when it carried a reason.
            throw refused != null && refused.regional()
                    ? refused : new PlaybackRefused(videoId, response);
        }

        if (response.live()) {
            return liveTrack(videoId, response, identity, sourceManager, discTitle, discAuthor, namesWin);
        }

        if (isBlank(response.serverAbrStreamingUrl()) || response.ustreamerConfig() == null) {
            return null;
        }

        Optional<InnerTubePlayer.AudioFormat> best = response.bestAudio();
        if (best.isEmpty()) return null;

        InnerTubePlayer.AudioFormat format = best.get();
        long durationMs = response.durationMs() > 0 ? response.durationMs() : format.durationMs();

        AudioTrackInfo info = trackInfo(videoId, response, discTitle, discAuthor,
                durationMs, namesWin);

        return new SabrAudioTrack(info, sourceManager, format.mimeType(),
                opener(response, format, durationMs));
    }

    private AudioTrack resolveDirect(String videoId, String discTitle, String discAuthor,
                                     boolean namesWin) throws PlaybackRefused {
        for (int attempt = 0; attempt < SESSION_ATTEMPTS; attempt++) {
            InnerTubePlayer.PlayerResponse response;
            String visitor;
            try {
                visitor = visitorData();
                response = new InnerTubePlayer(
                        http, InnerTubePlayer.ClientIdentity.visionOs(visitor)).fetch(videoId);
            } catch (Exception e) {
                return null;
            }

            if (!response.isPlayable()) throw new PlaybackRefused(videoId, response);

            try {
                if (response.live()) {
                    plans.put(videoId, Plan.VISION_ONLY);
                    return liveTrack(videoId, response, this::visionIdentity, visionSource,
                            discTitle, discAuthor, namesWin);
                }

                Optional<InnerTubePlayer.AudioFormat> best = response.bestAudio();
                if (best.isEmpty() || !best.get().hasDirectUrl()) return null;

                InnerTubePlayer.AudioFormat format = best.get();
                String url = descramble(format.directUrl(), format);

                // Some visitor sessions get links googlevideo refuses past the first bytes; the
                // fault is the session's, so one request for the middle clears it for every video.
                boolean verified = visitor != null && visitor.equals(verifiedVisitor);
                if (!verified && !servesMiddle(url, format.contentLength())) {
                    forgetVisitor();
                    continue;
                }
                if (visitor != null) verifiedVisitor = visitor;

                plans.put(videoId, response.playableInEmbed() ? Plan.VISION_THEN_EMBED : Plan.VISION_ONLY);
                long durationMs = response.durationMs() > 0 ? response.durationMs() : format.durationMs();
                return new DirectAudioTrack(
                        trackInfo(videoId, response, discTitle, discAuthor, durationMs, namesWin),
                        visionSource, cipherInterfaces, url, format.mimeType(), format.contentLength(),
                        () -> freshVisionUrl(videoId, format.itag()));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String freshVisionUrl(String videoId, int itag) throws IOException {
        forgetVisitor();
        InnerTubePlayer.PlayerResponse response = new InnerTubePlayer(
                http, InnerTubePlayer.ClientIdentity.visionOs(visitorDataOrNull())).fetch(videoId);

        for (InnerTubePlayer.AudioFormat format : response.audioFormats()) {
            if (format.itag() == itag && format.hasDirectUrl()) {
                return descramble(format.directUrl(), format);
            }
        }
        throw new IOException("YouTube no longer offers format " + itag + " of " + videoId);
    }

    private AudioTrack resolveEmbedded(String videoId, String discTitle, String discAuthor,
                                       boolean namesWin) throws IOException {
        EmbeddedPlayer.Answer answer = embedded.fetch(videoId);
        InnerTubePlayer.PlayerResponse response = answer.response();

        if (!response.isPlayable()) throw new PlaybackRefused(videoId, response);
        if (response.live()) return null;

        Optional<InnerTubePlayer.AudioFormat> best = response.audioFormats().stream()
                .filter(format -> format.hasDirectUrl() || answer.ciphers().containsKey(format.itag()))
                .max(Comparator.comparing(InnerTubePlayer.AudioFormat::mainTrack)
                        .thenComparing(InnerTubePlayer.AudioFormat::isOpus)
                        .thenComparingInt(InnerTubePlayer.AudioFormat::bitrate));
        if (best.isEmpty()) return null;

        InnerTubePlayer.AudioFormat format = best.get();
        String url = answer.urlOf(format, ownCipher);
        if (url == null || !servesMiddle(url, format.contentLength())) return null;

        long durationMs = response.durationMs() > 0 ? response.durationMs() : format.durationMs();
        return new DirectAudioTrack(
                trackInfo(videoId, response, discTitle, discAuthor, durationMs, namesWin),
                embeddedSource, cipherInterfaces, url, format.mimeType(), format.contentLength(),
                () -> freshEmbeddedUrl(videoId, format.itag()));
    }

    private String freshEmbeddedUrl(String videoId, int itag) throws IOException {
        EmbeddedPlayer.Answer answer = embedded.fetch(videoId);

        for (InnerTubePlayer.AudioFormat format : answer.response().audioFormats()) {
            if (format.itag() != itag) continue;
            String url = answer.urlOf(format, ownCipher);
            if (url != null) return url;
        }
        throw new IOException("the embedded player no longer offers format " + itag + " of " + videoId);
    }

    private boolean servesMiddle(String url, long contentLength) {
        if (contentLength <= 0) return true;

        long from = contentLength / 2;
        HttpGet request = new HttpGet(url);
        request.setHeader("Range", "bytes=" + from + "-" + Math.min(contentLength - 1, from + 1023));

        try (HttpInterface iface = cipherInterfaces.getInterface();
             CloseableHttpResponse response = iface.execute(request)) {
            EntityUtils.consumeQuietly(response.getEntity());
            int status = response.getStatusLine().getStatusCode();
            return status == 206 || status == 200;
        } catch (IOException e) {
            return true;
        }
    }

    private synchronized void forgetVisitor() {
        mintedVisitorData = null;
    }

    private InnerTubePlayer.ClientIdentity visionIdentity() {
        return InnerTubePlayer.ClientIdentity.visionOs(visitorDataOrNull());
    }

    private AudioTrack liveTrack(String videoId, InnerTubePlayer.PlayerResponse response,
                                 Supplier<InnerTubePlayer.ClientIdentity> who,
                                 AudioSourceManager source,
                                 String discTitle, String discAuthor, boolean namesWin) {
        Optional<InnerTubePlayer.AudioFormat> best = response.bestLiveAudio();
        if (best.isEmpty()) return null;

        AtomicReference<String> known = new AtomicReference<>(
                descramble(best.get().directUrl(), best.get()));

        return new LiveAudioTrack(
                trackInfo(videoId, response, discTitle, discAuthor, 0, namesWin),
                source, cipherInterfaces,
                () -> {
                    String first = known.getAndSet(null);
                    return first != null ? first : freshLiveUrl(videoId, who.get());
                });
    }

    private String freshLiveUrl(String videoId, InnerTubePlayer.ClientIdentity who) {
        try {
            InnerTubePlayer.PlayerResponse response = new InnerTubePlayer(http, who).fetch(videoId);
            InnerTubePlayer.AudioFormat format = response.bestLiveAudio().orElseThrow(
                    () -> new IOException("YouTube no longer offers an MP4 audio stream for " + videoId));

            return descramble(format.directUrl(), format);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized String visitorData() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (mintedVisitorData == null || now > mintedVisitorDataUntil) {
            accept(new dev.valkdz.cdisc.youtube.VisitorRenewal().mintPage(15), now);
        }
        return mintedVisitorData;
    }

    private synchronized void accept(dev.valkdz.cdisc.youtube.VisitorRenewal.Page page, long now) {
        mintedVisitorData = page.visitorData();
        mintedVisitorDataUntil = now + VISITOR_DATA_TTL_MS;
        if (page.region() != null) region = page.region();
    }

    public String region() {
        return region;
    }

    public void warmUp() {
        ownCipher.warmUp();
        new dev.valkdz.cdisc.youtube.VisitorRenewal().mintPageAsync(15)
                .thenAccept(page -> accept(page, System.currentTimeMillis()))
                .exceptionally(ignored -> null);

        CompletableFuture.runAsync(() -> {
            try (HttpInterface iface = cipherInterfaces.getInterface()) {
                cipher.getPlayerScript(iface);
            } catch (Exception ignored) {
            }
        });
    }

    public String visitorDataOrNull() {
        try {
            return visitorData();
        } catch (Exception e) {
            return null;
        }
    }

    private AudioTrackInfo trackInfo(String videoId, InnerTubePlayer.PlayerResponse response,
                                     String discTitle, String discAuthor, long durationMs,
                                     boolean namesWin) {
        return new AudioTrackInfo(
                namesWin ? pick(discTitle, response.title(), "Unknown title")
                        : pick(response.title(), discTitle, "Unknown title"),
                namesWin ? pick(discAuthor, response.author(), "Unknown artist")
                        : pick(response.author(), discAuthor, "Unknown artist"),
                response.live() ? Units.DURATION_MS_UNKNOWN : durationMs,
                videoId,
                response.live(),
                "https://www.youtube.com/watch?v=" + videoId);
    }

    private Supplier<SabrSeekableInputStream> opener(InnerTubePlayer.PlayerResponse response,
                                                     InnerTubePlayer.AudioFormat format,
                                                     long durationMs) {
        return () -> new SabrSeekableInputStream(format.contentLength(), () -> {
            InnerTubePlayer.ClientIdentity current = identity.get();

            SabrMessages.RequestState state = new SabrMessages.RequestState();
            state.format = format.formatId();
            state.videoFormat = response.cheapestVideo()
                    .map(InnerTubePlayer.AudioFormat::formatId).orElse(null);
            state.ustreamerConfig = response.ustreamerConfig();
            state.poToken = decodeToken(current.poToken());
            state.clientName = current.nameId();
            state.clientVersion = current.version();
            state.osName = current.osName();
            state.osVersion = current.osVersion();

            return new SabrInputStream(http, response.serverAbrStreamingUrl(),
                    durationMs, state, current.userAgent(),
                    target -> descramble(target, format));
        });
    }

    private String descramble(String url, InnerTubePlayer.AudioFormat format) {
        String n = queryParam(url, "n");
        if (n == null) return url;

        try {
            return ownCipher.resolve(ownCipher.currentPlayerId(), url, null, null);
        } catch (Exception ignored) {
        }

        try (HttpInterface iface = cipherInterfaces.getInterface()) {
            StreamFormat streamFormat = new StreamFormat(
                    ContentType.parse(format.mimeType()),
                    format.itag(), format.bitrate(), format.contentLength(), 2,
                    url, n, null, null, true, false);

            return cipher.resolveFormatUrl(iface, cipher.getPlayerScript(iface).url, streamFormat)
                    .toString();
        } catch (Exception e) {
            return url;
        }
    }

    private static String queryParam(String url, String key) {
        int start = url.indexOf('?');
        if (start < 0) return null;

        for (String pair : url.substring(start + 1).split("&")) {
            if (pair.startsWith(key + "=")) return pair.substring(key.length() + 1);
        }
        return null;
    }

    private static byte[] decodeToken(String poToken) {
        if (isBlank(poToken)) return null;

        try {
            String padded = poToken.length() % 4 == 0
                    ? poToken
                    : poToken + "=".repeat(4 - poToken.length() % 4);
            return java.util.Base64.getUrlDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pick(String first, String second, String fallback) {
        if (!isBlank(first)) return first;
        if (!isBlank(second)) return second;
        return fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
