package dev.valkdz.cdisc.audio;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiscordSource {

    public static final String ARTIST = "Discord";

    private static final Pattern ATTACHMENT = Pattern.compile(
            "^https?://(?:cdn[.]discordapp[.]com|media[.]discordapp[.]net)"
            + "/attachments/[0-9]+/[0-9]+/([^/?#]+)(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    public static final String[] URL_PREFIXES = {
            "https://cdn.discordapp.com/",
            "https://media.discordapp.net/",
            "http://cdn.discordapp.com/",
            "http://media.discordapp.net/",
    };

    private static final Pattern EXPIRY = Pattern.compile("[?&]ex=([0-9a-fA-F]+)");

    private static final int MAX_TITLE_LENGTH = 120;

    private static volatile HttpClient http;

    private DiscordSource() {
    }

    public static boolean isAttachment(String url) {
        return fileNameOf(url) != null;
    }

    public static String fileNameOf(String url) {
        if (url == null) return null;
        Matcher m = ATTACHMENT.matcher(url.trim());
        if (!m.matches()) return null;

        String name = m.group(1);
        try {
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {

        }
        name = name.trim();
        return name.isEmpty() ? null : name;
    }

    public static String titleOf(String url) {
        return stripExtension(clean(fileNameOf(url)));
    }

    public static String bestTitleOf(String url) {
        String fromUrl = titleOf(url);
        if (fromUrl == null) return null;

        String remote = stripExtension(clean(remoteFileName(url)));
        return remote == null ? fromUrl : remote;
    }

    private static String remoteFileName(String url) {
        if (!isDiscordUrl(url)) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "CDisc")
                    .build();

            HttpResponse<Void> response = http().send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) return null;

            return response.headers().firstValue("content-disposition")
                    .map(FileNames::filenameOf)
                    .orElse(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {

            return null;
        }
    }

    private static HttpClient http() {
        HttpClient client = http;
        if (client == null) {
            synchronized (DiscordSource.class) {
                client = http;
                if (client == null) {
                    client = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                            .connectTimeout(Duration.ofSeconds(5))

                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    http = client;
                }
            }
        }
        return client;
    }

    private static String clean(String raw) {
        if (raw == null) return null;

        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c < 0x20 || c == 0x7F || c == '§') continue;
            out.append(c);
            if (out.length() >= MAX_TITLE_LENGTH) break;
        }

        String name = out.toString().trim();
        return name.isEmpty() ? null : name;
    }

    private static String stripExtension(String name) {
        if (name == null) return null;

        int dot = name.lastIndexOf('.');

        if (dot > 0 && name.length() - dot <= 6) {
            String stem = name.substring(0, dot).trim();
            if (!stem.isEmpty()) return stem;
        }
        return name;
    }

    public static boolean hasExpired(String url) {
        long expiry = expiresAt(url);
        return expiry > 0 && expiry < System.currentTimeMillis() / 1000L;
    }

    public static long expiresAt(String url) {
        if (url == null) return -1;
        Matcher m = EXPIRY.matcher(url);
        if (!m.find()) return -1;
        try {
            return Long.parseLong(m.group(1), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean isDiscordUrl(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase(Locale.ROOT);
        for (String prefix : URL_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }
}
