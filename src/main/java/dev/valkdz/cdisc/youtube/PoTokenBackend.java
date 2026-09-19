package dev.valkdz.cdisc.youtube;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class PoTokenBackend {

    private static final long FALLBACK_TTL_SECONDS = 6 * 60 * 60;

    public record Pair(String poToken, String visitorData, long expiresInSeconds) {
    }

    private final HttpClient http;
    private final String userAgent;

    public PoTokenBackend(String userAgent) {
        this.userAgent = userAgent;
        this.http = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Pair fetch(String baseUrl, String password, int timeoutSeconds)
            throws IOException, InterruptedException {

        HttpResponse<String> response = ask(endpoint(baseUrl), password, timeoutSeconds);

        if (response.statusCode() == 404 && !hasPath(baseUrl)) {
            response = ask(trimSlash(baseUrl) + "/cdisc/public-youtube-data",
                    password, timeoutSeconds);
        }

        return read(response);
    }

    private HttpResponse<String> ask(String url, String password, int timeoutSeconds)
            throws IOException, InterruptedException {

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .GET();

        if (!password.isBlank()) {
            request.header("Authorization", "Bearer " + password);
        }

        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Pair read(HttpResponse<String> response) throws IOException {
        if (response.statusCode() == 401) {
            throw new IOException("the token server rejected the password"
                    + " — check po-token-backend.password in tokens.yml");
        }
        if (response.statusCode() != 200) {
            throw new IOException("the token server answered HTTP " + response.statusCode()
                    + describe(response.body()));
        }

        JsonBrowser body = JsonBrowser.parse(response.body());
        String poToken = body.get("poToken").text();
        String visitorData = body.get("visitorData").text();

        if (visitorData == null || visitorData.isBlank()) {
            throw new IOException("the token server answered without visitor data");
        }
        if (poToken == null) poToken = "";

        long ttl = body.get("expiresInSeconds").asLong(0);
        return new Pair(poToken, visitorData, ttl > 0 ? ttl : FALLBACK_TTL_SECONDS);
    }

    private static boolean hasPath(String baseUrl) {
        String path = URI.create(baseUrl.trim()).getPath();
        return path != null && !path.isEmpty() && !path.equals("/");
    }

    private static String trimSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String endpoint(String baseUrl) {
        String trimmed = baseUrl.trim();
        String path = URI.create(trimmed).getPath();

        if (path != null && !path.isEmpty() && !path.equals("/")) return trimmed;
        return trimmed.endsWith("/") ? trimmed + "token" : trimmed + "/token";
    }

    private static String describe(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            String error = JsonBrowser.parse(body).get("error").text();
            return error == null || error.isBlank() ? "" : " (" + error + ")";
        } catch (IOException e) {
            return "";
        }
    }
}
