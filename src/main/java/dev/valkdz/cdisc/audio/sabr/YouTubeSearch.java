package dev.valkdz.cdisc.audio.sabr;

import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class YouTubeSearch {

    private static final String SEARCH_URL = "https://www.youtube.com/youtubei/v1/search";

    public record Result(String videoId, String title, String channel, long durationSeconds) {

        public boolean isArtTrack() {
            return channel != null && channel.endsWith(" - Topic");
        }
    }

    private final HttpClient http;
    private final String clientVersion;

    public YouTubeSearch(HttpClient http, String clientVersion) {
        this.http = http;
        this.clientVersion = clientVersion == null || clientVersion.isBlank()
                ? "2.20260828.01.00" : clientVersion;
    }

    public List<Result> search(String query, String visitorData, int limit)
            throws IOException, InterruptedException {

        return parse(http.send(request(query, visitorData), HttpResponse.BodyHandlers.ofString()), limit);
    }

    public CompletableFuture<List<Result>> searchAsync(String query, String visitorData, int limit) {
        return http.sendAsync(request(query, visitorData), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        return parse(response, limit);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                });
    }

    private HttpRequest request(String query, String visitorData) {
        Json client = Json.object()
                .put("clientName", "WEB")
                .put("clientVersion", clientVersion)
                .put("hl", "en")
                .put("gl", "US");

        if (visitorData != null && !visitorData.isBlank()) {
            client.put("visitorData", visitorData);
        }

        Json body = Json.object();
        body.set("context", Json.object().set("client", client));
        body.put("query", query);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(SEARCH_URL))
                .header("Content-Type", "application/json")
                .header("X-Youtube-Client-Name", "1")
                .header("X-Youtube-Client-Version", clientVersion)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));

        if (visitorData != null && !visitorData.isBlank()) {
            request.header("X-Goog-Visitor-Id", visitorData);
        }
        return request.build();
    }

    private static List<Result> parse(HttpResponse<String> response, int limit) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException("YouTube search answered " + response.statusCode());
        }

        List<Result> results = new ArrayList<>();
        collect(Json.parse(response.body()), results, limit);
        return results;
    }

    private static void collect(Json node, List<Result> into, int limit) {
        if (into.size() >= limit) return;

        if (node.isObject()) {
            Json video = node.get("videoRenderer");
            if (video.isMissing()) video = node.get("compactVideoRenderer");
            if (!video.get("videoId").isNull()) {
                Result result = read(video);
                if (result != null) into.add(result);
                if (into.size() >= limit) return;
            }
            for (Json child : node) {
                collect(child, into, limit);
                if (into.size() >= limit) return;
            }
        } else if (node.isArray()) {
            for (Json child : node) {
                collect(child, into, limit);
                if (into.size() >= limit) return;
            }
        }
    }

    private static Result read(Json video) {
        String videoId = video.path("videoId").asText(null);
        if (videoId == null || videoId.isBlank()) return null;

        return new Result(videoId,
                firstRun(video.path("title")),
                firstRun(video.path("ownerText")),
                seconds(video.path("lengthText").path("simpleText").asText(null)));
    }

    private static String firstRun(Json node) {
        Json runs = node.path("runs");
        if (runs.isArray() && !runs.isEmpty()) return runs.get(0).path("text").asText(null);
        return node.path("simpleText").asText(null);
    }

    private static long seconds(String label) {
        if (label == null || label.isBlank()) return 0;

        long total = 0;
        for (String part : label.split(":")) {
            try {
                total = total * 60 + Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return total;
    }
}
