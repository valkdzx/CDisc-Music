package dev.valkdz.cdisc.util;

import dev.valkdz.cdisc.Main;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YoutubeAutoSettings {

    static final String STATE_FILE = "auto-settings.yml";

    private static final String TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace";

    private static final Set<String> RUSSIAN_ZONES = Set.of(
            "Europe/Moscow", "Europe/Kaliningrad", "Europe/Samara", "Europe/Volgograd",
            "Europe/Saratov", "Europe/Ulyanovsk", "Europe/Astrakhan", "Europe/Kirov",
            "Asia/Yekaterinburg", "Asia/Omsk", "Asia/Novosibirsk", "Asia/Barnaul", "Asia/Tomsk",
            "Asia/Novokuznetsk", "Asia/Krasnoyarsk", "Asia/Irkutsk", "Asia/Chita", "Asia/Yakutsk",
            "Asia/Khandyga", "Asia/Vladivostok", "Asia/Ust-Nera", "Asia/Magadan", "Asia/Sakhalin",
            "Asia/Srednekolymsk", "Asia/Kamchatka", "Asia/Anadyr", "W-SU");

    private YoutubeAutoSettings() {
    }

    static void run(Main plugin) {
        File sources = new File(plugin.getDataFolder(), SourcesConfig.FILE_NAME);
        if (!sources.exists()) return;

        File stateFile = new File(plugin.getDataFolder(), STATE_FILE);
        YamlConfiguration state = YamlConfiguration.loadConfiguration(stateFile);
        boolean dirty = false;

        // The marker is what lets an owner turn a setting back off: it is only ever set once.
        if (!state.contains("youtube.fallback-api")
                && switchOn(plugin, sources, "youtube", "fallback-api")) {
            state.set("youtube.fallback-api", "switched-on");
            dirty = true;
        }

        Boolean russia = null;
        boolean asked = false;
        for (String section : new String[]{"youtube", "soundcloud"}) {
            String marker = section + ".proxy";
            if (state.contains(marker)) continue;
            if (!asked) {
                russia = locatedInRussia();
                asked = true;
            }
            if (Boolean.FALSE.equals(russia)) {
                state.set(marker, "not-in-russia");
                dirty = true;
            } else if (Boolean.TRUE.equals(russia) && switchOn(plugin, sources, section, "proxy")) {
                state.set(marker, "switched-on");
                dirty = true;
            }
        }

        if (!dirty) return;
        state.options().setHeader(List.of(
                "Settings CDisc switched on by itself, each only once.",
                "Delete a line to let it happen again on the next start."));
        try {
            state.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't write " + STATE_FILE + ": " + e.getMessage());
        }
    }

    private static boolean switchOn(Main plugin, File file, String section, String key) {
        if (YamlConfiguration.loadConfiguration(file).getBoolean(section + "." + key, false)) {
            return true;
        }

        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String updated = replaceInSection(text, section, key);
            if (updated == null) return false;

            Files.writeString(file.toPath(), updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't switch on " + section + "." + key + ": " + e.getMessage());
            return false;
        }

        plugin.getLogger().info("Switched on " + section + "." + key + " in " + SourcesConfig.FILE_NAME
                + ". Set it back to false if you don't want it; it won't be changed again.");
        return true;
    }

    static String replaceInSection(String text, String section, String key) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\r?\\n", -1)));
        Pattern target = Pattern.compile("^(\\s+" + Pattern.quote(key) + ":\\s*)false(\\s*)$");

        boolean inSection = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")) {
                inSection = line.startsWith(section + ":");
                continue;
            }
            if (!inSection) continue;

            Matcher matcher = target.matcher(line);
            if (matcher.matches()) {
                lines.set(i, matcher.group(1) + "true" + matcher.group(2));
                return String.join(eol, lines);
            }
        }
        return null;
    }

    private static Boolean locatedInRussia() {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(TRACE_URL)).timeout(Duration.ofSeconds(4)).build(),
                    HttpResponse.BodyHandlers.ofString());

            for (String line : response.body().split("\n")) {
                if (line.startsWith("loc=")) return "RU".equalsIgnoreCase(line.substring(4).trim());
            }
        } catch (IOException e) {
            // Unreachable is itself common in Russia, so the clock gets the last word.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return RUSSIAN_ZONES.contains(ZoneId.systemDefault().getId()) ? Boolean.TRUE : null;
    }
}
