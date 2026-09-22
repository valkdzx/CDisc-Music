package dev.valkdz.cdisc.youtube;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VisitorRenewal {

    private static final Pattern VISITOR_DATA =
            Pattern.compile("\"visitorData\":\"([^\"]+)\"");

    // The country YouTube assigns to this server's address; geo blocks follow it, not gl.
    private static final Pattern GEOLOCATION =
            Pattern.compile("\"(?:GL|gl)\"\\s*:\\s*\"([A-Z]{2})\"");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    private final HttpClient http;

    public VisitorRenewal() {
        this.http = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record Page(String visitorData, String region) {}

    public String renew(String visitorId, int timeoutSeconds)
            throws IOException, InterruptedException {

        String visitorData = fetch(visitorId, timeoutSeconds).visitorData();
        String renewed = identityOf(visitorData);

        if (!visitorId.equals(renewed)) {
            throw new IOException("the identity was not honoured: asked for "
                    + visitorId + ", got " + renewed);
        }

        return visitorData;
    }

    public String mint(int timeoutSeconds) throws IOException, InterruptedException {
        return fetch(null, timeoutSeconds).visitorData();
    }

    public Page mintPage(int timeoutSeconds) throws IOException, InterruptedException {
        return fetch(null, timeoutSeconds);
    }

    public CompletableFuture<Page> mintPageAsync(int timeoutSeconds) {
        return http.sendAsync(request(null, timeoutSeconds), HttpResponse.BodyHandlers.ofString())
                .thenApply(VisitorRenewal::read);
    }

    private HttpRequest request(String visitorId, int timeoutSeconds) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("https://www.youtube.com/"))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                        .GET();

        if (visitorId != null) {
            request.header("Cookie", "VISITOR_INFO1_LIVE=" + visitorId);
        }
        return request.build();
    }

    private Page fetch(String visitorId, int timeoutSeconds)
            throws IOException, InterruptedException {

        try {
            return read(http.send(request(visitorId, timeoutSeconds),
                    HttpResponse.BodyHandlers.ofString()));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static Page read(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new UncheckedIOException(
                    new IOException("youtube.com answered " + response.statusCode()));
        }

        Matcher found = VISITOR_DATA.matcher(response.body());
        if (!found.find()) {
            throw new UncheckedIOException(
                    new IOException("the page carried no visitor data"));
        }

        Matcher region = GEOLOCATION.matcher(response.body());
        return new Page(found.group(1), region.find() ? region.group(1) : null);
    }

    public static String identityOf(String visitorData) {
        if (visitorData == null || visitorData.isBlank()) return null;

        try {
            String text = URLDecoder.decode(visitorData, StandardCharsets.UTF_8);
            int remainder = text.length() % 4;
            if (remainder != 0) text += "=".repeat(4 - remainder);

            byte[] raw = Base64.getUrlDecoder().decode(text);

            if (raw.length < 3 || raw[0] != 0x0a) return null;
            int length = raw[1];
            if (length <= 0 || length + 2 > raw.length) return null;

            return new String(raw, 2, length, StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
