package dev.valkdz.cdisc.command;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Config;
import dev.valkdz.cdisc.util.NetProxy;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class LogUpload {

    private static final URI ENDPOINT = URI.create("https://api.mclo.gs/1/log");
    private static final Path LOG = Path.of("logs", "latest.log");
    private static final int MAX_LINES = 25_000;
    private static final int MAX_CHARS = 9 * 1024 * 1024;
    private static final String HIDDEN = "[hidden by CDisc]";

    private static final Pattern GOOGLE_TOKEN = Pattern.compile("1//[0-9A-Za-z_-]{20,}|ya29\\.[0-9A-Za-z._-]+");
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9_%-]{100,}={0,2}");

    private LogUpload() {
    }

    static String upload(Main plugin, List<String> doctor) throws IOException, InterruptedException {
        List<String> log = Files.exists(LOG)
                ? List.of(new String(Files.readAllBytes(LOG), StandardCharsets.UTF_8).split("\r?\n"))
                : List.of("(" + LOG.toAbsolutePath() + " not found)");

        List<String> out = new ArrayList<>();
        out.add("CDisc " + plugin.getDescription().getVersion() + " on " + Bukkit.getName() + " "
                + Bukkit.getVersion() + ", Java " + System.getProperty("java.version")
                + ", " + System.getProperty("os.name"));
        out.add("");
        for (String line : doctor) out.add(line.replaceAll("[&§][0-9a-fk-orA-FK-OR]", ""));
        out.add("");
        out.add("===== " + LOG + " =====");

        int room = MAX_LINES - out.size();
        out.addAll(log.subList(Math.max(0, log.size() - room), log.size()));

        String content = hide(String.join("\n", out), plugin.cdiscConfig());
        if (content.length() > MAX_CHARS) content = content.substring(content.length() - MAX_CHARS);

        HttpClient http = NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "content=" + URLEncoder.encode(content, StandardCharsets.UTF_8)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonBrowser json = JsonBrowser.parse(response.body());
        if (response.statusCode() != 200 || !json.get("success").asBoolean(false)) {
            String error = json.get("error").text();
            throw new IOException(error != null ? error : "mclo.gs answered " + response.statusCode());
        }
        return json.get("url").text();
    }

    private static String hide(String text, Config config) {
        for (String secret : new String[]{
                config.getSpotifyClientSecret(), config.getPOtoken(), config.getVisitorData(),
                config.getYandexMusicAccessToken(), config.getVkMusicUserToken(),
                config.getRemoteCipherServerPassword(), config.getPoTokenBackendPassword(),
                config.getProxyUsername(), config.getProxyPassword()}) {
            if (secret != null && secret.length() >= 4) text = text.replace(secret, HIDDEN);
        }
        text = GOOGLE_TOKEN.matcher(text).replaceAll(HIDDEN);
        return LONG_TOKEN.matcher(text).replaceAll(HIDDEN);
    }
}
