package dev.valkdz.cdisc.util;

import dev.valkdz.cdisc.Main;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SourcesConfig {

    public static final String FILE_NAME = "sources.yml";

    private final Main plugin;
    private final File file;
    private FileConfiguration cfg;

    public SourcesConfig(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);

        InputStream bundled = plugin.getResource(FILE_NAME);
        if (bundled != null) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(bundled, StandardCharsets.UTF_8)));
        }
    }

    public void save() {
        try {
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe("Couldn't write " + FILE_NAME + ": " + e.getMessage());
        }
    }

    public boolean exists() {
        return file.exists();
    }

    public boolean isEnabled(String id, boolean fallback) {
        return cfg.getBoolean("enabled." + id, fallback);
    }

    public int searchDefaultResults() {
        return clampResults(cfg.getInt("search.default-results", 5), searchMaxResults(null));
    }

    public int searchMaxResults(String sourceId) {
        int global = clampResults(cfg.getInt("search.max-results", 10), 10);
        if (sourceId == null) return global;

        int perSource = cfg.getInt("search.per-source." + sourceId, global);
        return Math.min(global, clampResults(perSource, 10));
    }

    private static int clampResults(int value, int ceiling) {
        return Math.max(1, Math.min(value, ceiling));
    }

    public String localFolder() {
        String folder = cfg.getString("local.folder", "local");
        return folder == null || folder.isBlank() ? "local" : folder.trim();
    }

    public Set<String> localExtensions() {
        List<String> configured = cfg.getStringList("local.extensions");
        if (configured.isEmpty()) {
            configured = List.of("mp3", "ogg", "flac", "wav", "m4a", "aac", "opus", "webm");
        }

        Set<String> types = new HashSet<>();
        for (String entry : configured) {
            if (entry == null) continue;
            String clean = entry.trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith(".")) clean = clean.substring(1);
            if (!clean.isEmpty()) types.add(clean);
        }
        return Set.copyOf(types);
    }

    public int localMaxFiles() {
        return Math.max(1, cfg.getInt("local.max-files", 5000));
    }

    public boolean downloadEnabled() {
        return cfg.getBoolean("local.download.enabled", true);
    }

    public long downloadMaxBytes() {
        return Math.max(1L, cfg.getLong("local.download.max-size-mb", 100)) * 1024L * 1024L;
    }

    public int downloadTimeoutSeconds() {
        return Math.max(5, cfg.getInt("local.download.timeout-seconds", 120));
    }

    public List<String> youtubeClients() {
        List<String> configured = cfg.getStringList("youtube.clients");
        List<String> names = new ArrayList<>();

        for (String entry : configured) {
            if (entry == null) continue;
            String clean = entry.trim().toLowerCase(Locale.ROOT);
            if (!clean.isEmpty()) names.add(clean);
        }
        return names;
    }

    public boolean youtubeLogClientFailures() {
        return cfg.getBoolean("youtube.log-client-failures", false);
    }

    public String youtubeSetupGuideUrl() {
        // No fallback argument on purpose: Bukkit consults setDefaults only when
        // getString is called without one, and the bundled value is the point here.
        String url = cfg.getString("youtube.setup-guide-url");
        return url == null ? "" : url.trim();
    }

    public boolean youtubeFallbackApi() {
        return cfg.getBoolean("youtube.fallback-api", true);
    }

    public boolean youtubeProxy() {
        return cfg.getBoolean("youtube.proxy", false);
    }

    public int youtubeSignatureTimestamp() {
        return cfg.getInt("youtube.signature-timestamp", 20684);
    }

    public boolean youtubeSabr() {
        return cfg.getBoolean("youtube.sabr", true);
    }

    public String youtubeWebClientVersion() {
        String version = cfg.getString("youtube.web-client-version");
        return version == null || version.isBlank() ? "2.20260828.01.00" : version.trim();
    }

    public boolean youtubeFastCreate() {
        return cfg.getBoolean("youtube.fast-create", true);
    }

    public int youtubeProbeTimeoutSeconds() {
        return Math.max(1, cfg.getInt("youtube.probe-timeout-seconds", 4));
    }

    public String remoteCipherUrl() {

        String url = cfg.getString("youtube.remote-cipher.url");
        return url == null ? "" : url.trim();
    }

    public String poTokenBackendUrl() {
        return cfg.getString("youtube.po-token-backend.url",
                "https://2281273.xyz/cdisc/public-youtube-data").trim();
    }

    public int poTokenTimeoutSeconds() {
        return Math.max(5, cfg.getInt("youtube.po-token-backend.timeout-seconds", 60));
    }

    FileConfiguration raw() {
        return cfg;
    }
}
