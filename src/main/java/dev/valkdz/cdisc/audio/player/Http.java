package dev.valkdz.cdisc.audio.player;

import dev.valkdz.cdisc.util.Json;
import dev.valkdz.cdisc.util.NetProxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class Http {

    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final HttpClient CLIENT = NetProxy.apply(HttpClient.newBuilder())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final HttpClient NO_REDIRECTS = NetProxy.apply(HttpClient.newBuilder())
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Http() {
    }

    public static HttpClient client() {
        return CLIENT;
    }

    public static HttpRequest.Builder request(String url, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT);
        for (int i = 0; i + 1 < headers.length; i += 2) builder.setHeader(headers[i], headers[i + 1]);
        return builder;
    }

    public static HttpResponse<byte[]> send(HttpRequest request) throws IOException {
        return send(CLIENT, request, HttpResponse.BodyHandlers.ofByteArray());
    }

    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        try {
            return client.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + request.uri().getHost(), e);
        }
    }

    public static byte[] bytes(String url, String... headers) throws IOException {
        HttpResponse<byte[]> response = send(request(url, headers).GET().build());
        expectOk(response, url);
        return response.body();
    }

    public static String text(String url, String... headers) throws IOException {
        return new String(bytes(url, headers), StandardCharsets.UTF_8);
    }

    public static Json json(String url, String... headers) throws IOException {
        return Json.parse(text(url, headers));
    }

    public static String redirectOf(String url, String... headers) throws IOException {
        HttpResponse<Void> response = send(NO_REDIRECTS, request(url, headers).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            String location = response.headers().firstValue("Location").orElse(null);
            return location == null ? null : URI.create(url).resolve(location).toString();
        }
        return null;
    }

    public static HttpResponse<InputStream> open(String url, long from, long to, String... headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(TIMEOUT);
        for (int i = 0; i + 1 < headers.length; i += 2) builder.setHeader(headers[i], headers[i + 1]);
        if (from > 0 || to >= 0) builder.header("Range", "bytes=" + from + "-" + (to >= 0 ? String.valueOf(to) : ""));
        return send(CLIENT, builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    public static void expectOk(HttpResponse<?> response, String what) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + URI.create(response.request().uri().toString()).getHost()
                    + (what == null ? "" : " for " + shorten(what)));
        }
    }

    private static String shorten(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
