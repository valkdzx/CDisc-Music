package dev.valkdz.cdisc.youtube;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VisitorRenewal {

    private static final Pattern VISITOR_DATA =
            Pattern.compile("\"visitorData\":\"([^\"]+)\"");

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

    public String renew(String visitorId, int timeoutSeconds)
            throws IOException, InterruptedException {

        String visitorData = fetch(visitorId, timeoutSeconds);
        String renewed = identityOf(visitorData);

        if (!visitorId.equals(renewed)) {
            throw new IOException("the identity was not honoured: asked for "
                    + visitorId + ", got " + renewed);
        }

        return visitorData;
    }

    public String mint(int timeoutSeconds) throws IOException, InterruptedException {
        return fetch(null, timeoutSeconds);
    }

    private String fetch(String visitorId, int timeoutSeconds)
            throws IOException, InterruptedException {

        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("https://www.youtube.com/"))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                        .GET();

        if (visitorId != null) {
            request.header("Cookie", "VISITOR_INFO1_LIVE=" + visitorId);
        }

        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("youtube.com answered " + response.statusCode());
        }

        Matcher found = VISITOR_DATA.matcher(response.body());
        if (!found.find()) {
            throw new IOException("the page carried no visitor data");
        }

        return found.group(1);
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
