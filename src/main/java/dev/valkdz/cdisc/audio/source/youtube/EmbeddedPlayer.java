package dev.valkdz.cdisc.audio.source.youtube;

import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EmbeddedPlayer {

    private static final Pattern YTCFG = Pattern.compile("ytcfg\\.set\\s*\\(\\s*(\\{.+?\\})\\s*\\)\\s*;", Pattern.DOTALL);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)";

    // Any page that is not YouTube's own: the embed client is refused without a host.
    private static final String HOST_PAGE = "https://www.reddit.com/";

    record Answer(InnerTubePlayer.PlayerResponse response, String playerId, Map<Integer, String> ciphers) {

        String urlOf(InnerTubePlayer.AudioFormat format, YoutubeCipher cipher) throws IOException {
            if (format.hasDirectUrl()) return cipher.resolve(playerId, format.directUrl(), null, null);

            String scrambled = ciphers.get(format.itag());
            if (scrambled == null) return null;

            Map<String, String> parts = query(scrambled);
            String url = parts.get("url");
            if (url == null) return null;
            return cipher.resolve(playerId, url, parts.get("s"), parts.get("sp"));
        }
    }

    private final HttpClient http;
    private final YoutubeCipher cipher;

    EmbeddedPlayer(HttpClient http, YoutubeCipher cipher) {
        this.http = http;
        this.cipher = cipher;
    }

    Answer fetch(String videoId) throws IOException {
        String page = send(HttpRequest.newBuilder(URI.create("https://www.youtube.com/embed/" + videoId + "?html5=1"))
                .header("User-Agent", USER_AGENT)
                .header("Referer", HOST_PAGE)
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET());

        Json config = ytcfg(page);
        String playerId = playerFor(page);
        if (config == null || playerId == null) throw new IOException("the embed page carried no player config");

        Json context = config.path("INNERTUBE_CONTEXT").deepCopy();
        context.set("thirdParty", Json.object().put("embedUrl", HOST_PAGE));
        Json client = context.path("client");

        Json playback = Json.object()
                .put("html5Preference", "HTML5_PREF_WANTS")
                .put("signatureTimestamp", cipher.signatureTimestamp(playerId));
        String hostFlags = config.path("WEB_PLAYER_CONTEXT_CONFIGS")
                .path("WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER").path("encryptedHostFlags").asText(null);
        if (hostFlags != null) playback.put("encryptedHostFlags", hostFlags);

        Json body = Json.object();
        body.set("context", context);
        body.put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true);
        body.set("playbackContext", Json.object().set("contentPlaybackContext", playback));

        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("https://www.youtube.com/youtubei/v1/player?prettyPrint=false"))
                .header("content-type", "application/json")
                .header("user-agent", USER_AGENT)
                .header("origin", "https://www.youtube.com")
                .header("referer", HOST_PAGE)
                .header("x-youtube-client-name", config.path("INNERTUBE_CONTEXT_CLIENT_NAME").asText("56"))
                .header("x-youtube-client-version", client.path("clientVersion").asText(""))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));

        String visitor = client.path("visitorData").asText(config.path("VISITOR_DATA").asText(""));
        if (!visitor.isBlank()) request.header("x-goog-visitor-id", visitor);

        Json root = Json.parse(send(request));

        Map<Integer, String> ciphers = new HashMap<>();
        for (Json format : root.path("streamingData").path("adaptiveFormats")) {
            String scrambled = format.path("signatureCipher").asText(null);
            if (scrambled != null) ciphers.put(format.path("itag").asInt(), scrambled);
        }
        return new Answer(InnerTubePlayer.parse(root), playerId, ciphers);
    }

    private String playerFor(String page) {
        try {
            return cipher.currentPlayerId();
        } catch (IOException e) {
            return YoutubeCipher.playerIdIn(page);
        }
    }

    private String send(HttpRequest.Builder request) throws IOException {
        try {
            HttpResponse<String> response = http.send(request.timeout(TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("the embedded player answered " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while asking the embedded player", e);
        }
    }

    private static Json ytcfg(String page) {
        Json merged = Json.object();
        boolean found = false;

        Matcher sets = YTCFG.matcher(page);
        while (sets.find()) {
            try {
                Json part = Json.parse(sets.group(1));
                if (part.isObject()) {
                    merged.setAll(part);
                    found = true;
                }
            } catch (IOException ignored) {
            }
        }
        return found ? merged : null;
    }

    private static Map<String, String> query(String text) {
        Map<String, String> out = new HashMap<>();
        for (String pair : text.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
