package dev.valkdz.cdisc.audio.sabr;

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
import org.apache.http.entity.ContentType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SabrResolver {

    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private static final Pattern BARE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private final HttpClient http;
    private final SabrSourceManager sourceManager = new SabrSourceManager();
    private final Supplier<InnerTubePlayer.ClientIdentity> identity;

    private final CipherManager cipher;

    private static final long VISITOR_DATA_TTL_MS = 60 * 60 * 1000L;

    private String mintedVisitorData;
    private long mintedVisitorDataUntil;
    private final HttpInterfaceManager cipherInterfaces = HttpClientTools.createDefaultThreadLocalManager();

    public SabrResolver(Supplier<InnerTubePlayer.ClientIdentity> identity, String remoteCipherUrl) {
        this.identity = identity;
        this.cipher = isBlank(remoteCipherUrl)
                ? new LocalSignatureCipherManager()
                : new RemoteCipherManager(remoteCipherUrl);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))

                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
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
        String videoId = videoIdOf(identifier);
        if (videoId == null) return null;

        AudioTrack direct = resolveDirect(videoId, discTitle, discAuthor, namesWin);
        if (direct != null) return direct;

        InnerTubePlayer.PlayerResponse response = new InnerTubePlayer(http, identity.get()).fetch(videoId);

        if (!response.isPlayable()) {
            throw new IOException("YouTube will not play " + videoId + ": "
                    + response.playabilityStatus()
                    + (response.playabilityReason() == null ? "" : " — " + response.playabilityReason()));
        }

        if (response.live()) {
            return liveTrack(videoId, response, identity, discTitle, discAuthor, namesWin);
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
                                     boolean namesWin) {
        try {
            InnerTubePlayer.PlayerResponse response = new InnerTubePlayer(
                    http, InnerTubePlayer.ClientIdentity.visionOs(visitorData())).fetch(videoId);

            if (!response.isPlayable()) return null;

            if (response.live()) {
                return liveTrack(videoId, response, this::visionIdentity,
                        discTitle, discAuthor, namesWin);
            }

            Optional<InnerTubePlayer.AudioFormat> best = response.bestAudio();
            if (best.isEmpty() || !best.get().hasDirectUrl()) return null;

            InnerTubePlayer.AudioFormat format = best.get();
            long durationMs = response.durationMs() > 0 ? response.durationMs() : format.durationMs();

            return new DirectAudioTrack(
                    trackInfo(videoId, response, discTitle, discAuthor, durationMs, namesWin),
                    sourceManager, cipherInterfaces, descramble(format.directUrl(), format),
                    format.mimeType(), format.contentLength());
        } catch (Exception e) {
            return null;
        }
    }

    private InnerTubePlayer.ClientIdentity visionIdentity() {
        return InnerTubePlayer.ClientIdentity.visionOs(visitorDataOrNull());
    }

    private AudioTrack liveTrack(String videoId, InnerTubePlayer.PlayerResponse response,
                                 Supplier<InnerTubePlayer.ClientIdentity> who,
                                 String discTitle, String discAuthor, boolean namesWin) {
        Optional<InnerTubePlayer.AudioFormat> best = response.bestLiveAudio();
        if (best.isEmpty()) return null;

        AtomicReference<String> known = new AtomicReference<>(
                descramble(best.get().directUrl(), best.get()));

        return new LiveAudioTrack(
                trackInfo(videoId, response, discTitle, discAuthor, 0, namesWin),
                sourceManager, cipherInterfaces,
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
            mintedVisitorData = new dev.valkdz.cdisc.youtube.VisitorRenewal().mint(15);
            mintedVisitorDataUntil = now + VISITOR_DATA_TTL_MS;
        }
        return mintedVisitorData;
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
