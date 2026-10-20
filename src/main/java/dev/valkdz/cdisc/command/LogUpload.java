package dev.valkdz.cdisc.command;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.config.Config;
import dev.valkdz.cdisc.util.Json;
import dev.valkdz.cdisc.util.NetProxy;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LogUpload {

    private static final URI ENDPOINT = URI.create("https://api.mclo.gs/1/log");
    private static final Path LOG = Path.of("logs", "latest.log");
    private static final int MAX_CHARS = 9 * 1024 * 1024;
    private static final int MAX_PROBLEMS = 40;
    private static final int MAX_ENTRY_LINES = 80;
    private static final Set<String> PROBLEM_LEVELS = Set.of("WARN", "WARNING", "ERROR", "SEVERE", "FATAL");
    // Paper "[12:34:56 WARN]:" and vanilla or Spigot "[12:34:56] [Server thread/WARN]:".
    private static final Pattern ENTRY = Pattern.compile(
            "^\\[\\d{2}:\\d{2}:\\d{2}(?: (\\w+)\\]|\\] \\[[^\\]]*/(\\w+)\\])");
    private static final String ISSUES = "https://github.com/valkdzx/CDisc-Music/issues";
    private static final String HIDDEN = "[hidden by CDisc]";

    private static final Pattern GOOGLE_TOKEN = Pattern.compile("1//[0-9A-Za-z_-]{20,}|ya29\\.[0-9A-Za-z._-]+");
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9_%-]{100,}={0,2}");
    private static final Pattern DOTTED_TOKEN = Pattern.compile(
            "\\b[MNO][A-Za-z0-9_-]{23,27}\\.[A-Za-z0-9_-]{6}\\.[A-Za-z0-9_-]{27,40}\\b"
                    + "|\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");
    // A YAML parse error quotes the broken line of the file, a token in tokens.yml included.
    private static final Pattern YAML_EXCERPT = Pattern.compile("(?m)^([ \\t]+[^\\s:#][^:\\n]*:[ \\t]*)\\S[^\\n]*(\\r?\\n[ \\t]*\\^)");

    private LogUpload() {
    }

    static String upload(Main plugin, List<String> doctor) throws IOException, InterruptedException {
        if (!Files.exists(LOG)) return null;
        List<String> problems;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(LOG), StandardCharsets.UTF_8))) {
            problems = problems(reader.lines().iterator());
        }
        if (problems.isEmpty()) return null;

        List<String> out = new ArrayList<>();
        out.add("Uploaded with /cdisc admin logs for a CDisc issue report: " + ISSUES);
        out.add("CDisc " + plugin.getDescription().getVersion() + " on " + Bukkit.getName() + " "
                + Bukkit.getVersion() + ", Java " + System.getProperty("java.version")
                + ", " + System.getProperty("os.name"));
        out.add("");
        for (String line : doctor) out.add(line.replaceAll("[&§][0-9a-fk-orA-FK-OR]", ""));
        out.add("");
        out.add("===== CDisc warnings and errors in " + LOG + " =====");
        out.addAll(problems);

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
        Json json = Json.parse(response.body());
        if (response.statusCode() != 200 || !json.get("success").asBoolean(false)) {
            String error = json.get("error").text();
            throw new IOException(error != null ? error : "mclo.gs answered " + response.statusCode());
        }
        return json.get("url").text();
    }

    private record Problem(List<String> lines, int times) {
    }

    static List<String> problems(Iterator<String> log) {
        Map<String, Problem> found = new LinkedHashMap<>();
        int dropped = 0;
        List<String> entry = new ArrayList<>();
        boolean problem = false;
        int cut = 0;

        while (true) {
            String line = log.hasNext() ? log.next() : null;
            Matcher start = line == null ? null : ENTRY.matcher(line);
            if (line != null && !start.find()) {
                if (problem && entry.size() < MAX_ENTRY_LINES) entry.add(line);
                else if (problem) cut++;
                continue;
            }

            if (problem && mentionsCdisc(entry)) {
                if (cut > 0) entry.add("\t... " + cut + " more lines");
                String key = String.join("\n", entry).replaceFirst("^\\[\\d{2}:\\d{2}:\\d{2}", "[");
                Problem known = found.remove(key);
                found.put(key, new Problem(entry, known == null ? 1 : known.times() + 1));
                if (found.size() > MAX_PROBLEMS) {
                    found.remove(found.keySet().iterator().next());
                    dropped++;
                }
            }
            if (line == null) break;

            entry = new ArrayList<>();
            entry.add(line);
            cut = 0;
            String level = start.group(1) != null ? start.group(1) : start.group(2);
            problem = PROBLEM_LEVELS.contains(level.toUpperCase(Locale.ROOT));
        }

        List<String> out = new ArrayList<>();
        if (dropped > 0) out.add("(" + dropped + " older warnings and errors left out)");
        for (Problem kept : found.values()) {
            if (!out.isEmpty()) out.add("");
            out.addAll(kept.lines());
            if (kept.times() > 1) out.add("(" + kept.times() + " times in this log, the last one shown)");
        }
        return out;
    }

    private static boolean mentionsCdisc(List<String> entry) {
        for (String line : entry) {
            if (line.toLowerCase(Locale.ROOT).contains("cdisc")) return true;
        }
        return false;
    }

    private static String hide(String text, Config config) {
        for (String secret : new String[]{
                config.getSpotifyClientSecret(), config.getPOtoken(), config.getVisitorData(),
                config.getYandexMusicAccessToken(), config.getVkMusicUserToken(),
                config.getRemoteCipherServerPassword(), config.getPoTokenBackendPassword(),
                config.getProxyUsername(), config.getProxyPassword()}) {
            if (secret != null && secret.length() >= 4) text = text.replace(secret, HIDDEN);
        }
        return hideTokens(text);
    }

    static String hideTokens(String text) {
        text = GOOGLE_TOKEN.matcher(text).replaceAll(HIDDEN);
        text = DOTTED_TOKEN.matcher(text).replaceAll(HIDDEN);
        text = YAML_EXCERPT.matcher(text).replaceAll("$1" + HIDDEN + "$2");
        return LONG_TOKEN.matcher(text).replaceAll(HIDDEN);
    }
}
