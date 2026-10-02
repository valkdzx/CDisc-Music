package dev.valkdz.cdisc.audio.sabr;

import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class InnerTubePlayer {

    private static final String PLAYER_URL = "https://www.youtube.com/youtubei/v1/player";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);


    private final HttpClient http;
    private final ClientIdentity identity;

    public InnerTubePlayer(HttpClient http, ClientIdentity identity) {
        this.http = http;
        this.identity = identity;
    }

    public record ClientIdentity(String name, int nameId, String version,
                                 String osName, String osVersion, String userAgent,
                                 String deviceMake, String deviceModel,
                                 String poToken, String visitorData, String oauthToken,
                                 int signatureTimestamp) {

        public static ClientIdentity web(String version, String userAgent,
                                         String poToken, String visitorData, String oauthToken,
                                         int signatureTimestamp) {
            return new ClientIdentity("WEB", 1, version, "Windows", "10.0",
                    userAgent, null, null,
                    poToken, visitorData, oauthToken, signatureTimestamp);
        }

        public static ClientIdentity visionOs(String visitorData) {
            return new ClientIdentity("VISIONOS", 101, VISION_VERSION,
                    "visionOS", VISION_OS_VERSION, VISION_USER_AGENT,
                    "Apple", "RealityDevice17,1",
                    null, visitorData, null, 0);
        }
    }

    private static final String VISION_VERSION = "1.02";
    private static final String VISION_OS_VERSION = "26.5.23O471";
    private static final String VISION_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/26.0 Safari/605.1.15";

    public record AudioFormat(int itag, long lastModified, String mimeType, String xtags,
                              long contentLength, int bitrate, long durationMs,
                              String directUrl, boolean mainTrack) {

        public boolean isOpus() {
            return mimeType != null && mimeType.contains("opus");
        }

        public boolean hasDirectUrl() {
            return directUrl != null && !directUrl.isEmpty();
        }

        public SabrMessages.FormatId formatId() {
            return new SabrMessages.FormatId(itag, lastModified, xtags);
        }
    }

    public record PlayerResponse(String playabilityStatus, String playabilityReason,
                                 String playabilitySubreason,
                                 String title, String author, long durationMs, boolean live,
                                 String serverAbrStreamingUrl, byte[] ustreamerConfig,
                                 List<AudioFormat> audioFormats, List<AudioFormat> videoFormats,
                                 boolean playableInEmbed) {

        public Optional<AudioFormat> cheapestVideo() {
            return videoFormats.stream().min(Comparator.comparingInt(AudioFormat::bitrate));
        }

        public boolean isPlayable() {
            return playabilityStatus == null || "OK".equals(playabilityStatus);
        }

        public boolean isSabrOnly() {
            return !audioFormats.isEmpty() && audioFormats.stream().noneMatch(AudioFormat::hasDirectUrl);
        }

        public Optional<AudioFormat> bestAudio() {
            return audioFormats.stream().max(
                    Comparator.comparing(AudioFormat::mainTrack)
                            .thenComparing(AudioFormat::isOpus)
                            .thenComparingInt(AudioFormat::bitrate));
        }

        public Optional<AudioFormat> bestLiveAudio() {
            // Live is read as MP4 fragments, so a WebM/Opus format is no use here.
            return audioFormats.stream()
                    .filter(AudioFormat::hasDirectUrl)
                    .filter(format -> format.mimeType() != null
                            && format.mimeType().startsWith("audio/mp4"))
                    .max(Comparator.comparing(AudioFormat::mainTrack)
                            .thenComparingInt(AudioFormat::bitrate));
        }
    }

    public PlayerResponse fetch(String videoId) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(PLAYER_URL + "?prettyPrint=false"))
                .timeout(TIMEOUT)
                .header("content-type", "application/json")
                .header("user-agent", identity.userAgent())
                .header("origin", "https://www.youtube.com")
                .header("referer", "https://www.youtube.com/")
                .header("x-youtube-client-name", String.valueOf(identity.nameId()))
                .header("x-youtube-client-version", identity.version())
                .POST(HttpRequest.BodyPublishers.ofString(body(videoId), StandardCharsets.UTF_8));

        if (notBlank(identity.visitorData())) {
            builder.header("x-goog-visitor-id", identity.visitorData());
        }
        if (notBlank(identity.oauthToken())) {
            builder.header("authorization", "Bearer " + identity.oauthToken());
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while asking YouTube for a player response", e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("Player endpoint answered " + response.statusCode());
        }
        return parse(Json.parse(response.body()));
    }

    private String body(String videoId) {
        Json client = Json.object()
                .put("clientName", identity.name())
                .put("clientVersion", identity.version())
                .put("osName", identity.osName())
                .put("osVersion", identity.osVersion())
                .put("hl", "en")
                .put("gl", "US")
                .put("userAgent", identity.userAgent());

        if (notBlank(identity.deviceMake())) client.put("deviceMake", identity.deviceMake());
        if (notBlank(identity.deviceModel())) client.put("deviceModel", identity.deviceModel());

        if (notBlank(identity.visitorData())) {
            client.put("visitorData", identity.visitorData());
        }

        Json request = Json.object();
        request.set("context", Json.object().set("client", client));
        request.put("videoId", videoId);

        request.put("contentCheckOk", true);
        request.put("racyCheckOk", true);

        if (identity.signatureTimestamp() > 0) {
            Json playback = Json.object()
                    .put("html5Preference", "HTML5_PREF_WANTS")
                    .put("signatureTimestamp", identity.signatureTimestamp());
            request.set("playbackContext",
                    Json.object().set("contentPlaybackContext", playback));
        }

        if (notBlank(identity.poToken())) {
            request.set("serviceIntegrityDimensions",
                    Json.object().put("poToken", identity.poToken()));
        }
        return request.toString();
    }

    static PlayerResponse parse(Json root) {
        Json playability = root.path("playabilityStatus");
        Json streaming = root.path("streamingData");
        Json details = root.path("videoDetails");

        String encodedConfig = root.path("playerConfig")
                .path("mediaCommonConfig")
                .path("mediaUstreamerRequestConfig")
                .path("videoPlaybackUstreamerConfig")
                .asText(null);

        byte[] ustreamerConfig = null;
        if (notBlank(encodedConfig)) {
            ustreamerConfig = Base64.getUrlDecoder().decode(pad(encodedConfig));
        }

        long durationMs = details.path("lengthSeconds").asLong(0) * 1000L;
        boolean live = details.path("isLive").asBoolean(false);

        List<AudioFormat> audio = new ArrayList<>();
        List<AudioFormat> video = new ArrayList<>();
        for (Json format : streaming.path("adaptiveFormats")) {
            String mimeType = format.path("mimeType").asText("");
            boolean isAudio = mimeType.startsWith("audio/");
            if (!isAudio && !mimeType.startsWith("video/")) continue;

            (isAudio ? audio : video).add(new AudioFormat(
                    format.path("itag").asInt(),
                    format.path("lastModified").asLong(0),
                    mimeType,
                    format.path("xtags").asText(null),
                    format.path("contentLength").asLong(0),
                    format.path("bitrate").asInt(0),
                    format.path("approxDurationMs").asLong(0),
                    format.path("url").asText(null),
                    isMainTrack(format)));
        }

        return new PlayerResponse(
                playability.path("status").asText(null),
                playability.path("reason").asText(null),
                textOf(playability.path("errorScreen")
                        .path("playerErrorMessageRenderer").path("subreason")),
                details.path("title").asText(null),
                details.path("author").asText(null),
                durationMs,
                live,
                streaming.path("serverAbrStreamingUrl").asText(null),
                ustreamerConfig,
                audio, video,
                playability.path("playableInEmbed").asBoolean(true));
    }

    // A dubbed video lists every language, and a DRC copy of each, beside the original.
    private static boolean isMainTrack(Json format) {
        if (format.path("isDrc").asBoolean(false)) return false;
        Json track = format.path("audioTrack");
        return track.isMissingNode() || track.path("audioIsDefault").asBoolean(false);
    }

    private static String textOf(Json node) {
        if (node.isMissingNode()) return null;

        String simple = node.path("simpleText").asText(null);
        if (notBlank(simple)) return simple;

        StringBuilder joined = new StringBuilder();
        for (Json run : node.path("runs")) {
            joined.append(run.path("text").asText(""));
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    private static String pad(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
