package dev.valkdz.cdisc.util;

import dev.valkdz.cdisc.Main;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Tokens {

    public static final String FILE_NAME = "tokens.yml";

    private static final String SPOTIFY = "spotify";
    private static final String YANDEX = "yandex-music";
    private static final String VK = "vk-music";
    private static final String YOUTUBE = "youtube";
    private static final String CIPHER = "remote-cipher-server";
    private static final String PO_TOKEN_BACKEND = "po-token-backend";

    private final Main plugin;
    private final File file;

    private FileConfiguration cfg;

    public Tokens(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);

        TokensJsonMigration.run(plugin);

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

    public String spotifyClientId() { return string(SPOTIFY, "client-id"); }
    public String spotifyClientSecret() { return string(SPOTIFY, "client-secret"); }
    public String yandexAccessToken() { return string(YANDEX, "access-token"); }
    public String vkUserToken() { return string(VK, "user-token"); }

    public boolean youtubeOauthEnabled() { return bool(YOUTUBE, "oauth-enabled", false); }
    public String youtubeOauthSetupDone() { return raw(YOUTUBE, "oauth-setup-done", "false"); }
    public String youtubeRefreshToken() { return string(YOUTUBE, "refresh-token"); }
    public String youtubePoToken() { return string(YOUTUBE, "po-token"); }
    public String youtubeVisitorData() { return string(YOUTUBE, "visitor-data"); }
    public String cipherPassword() { return string(CIPHER, "password"); }
    public String poTokenBackendPassword() { return string(PO_TOKEN_BACKEND, "password"); }

    public String poTokenBackendUrl() { return string(PO_TOKEN_BACKEND, "url"); }

    public void setYoutubeOauthEnabled(boolean enabled) { set(YOUTUBE, "oauth-enabled", enabled); }
    public void setYoutubeOauthSetupDone(String value) { set(YOUTUBE, "oauth-setup-done", value); }
    public void setYoutubeRefreshToken(String token) { set(YOUTUBE, "refresh-token", token); }

    public void setSpotify(String clientId, String clientSecret) {
        set(SPOTIFY, "client-id", clientId);
        set(SPOTIFY, "client-secret", clientSecret);
    }

    public void setYandexAccessToken(String token) { set(YANDEX, "access-token", token); }
    public void setVkUserToken(String token) { set(VK, "user-token", token); }
    public void setYoutubePoToken(String token) { set(YOUTUBE, "po-token", token); }
    public void setYoutubeVisitorData(String data) { set(YOUTUBE, "visitor-data", data); }
    public void setCipherPassword(String password) { set(CIPHER, "password", password); }

    private String string(String section, String key) {
        return normalise(raw(section, key, ""));
    }

    private String raw(String section, String key, String fallback) {
        return cfg.getString(path(section, key), fallback);
    }

    private boolean bool(String section, String key, boolean fallback) {
        return cfg.getBoolean(path(section, key), fallback);
    }

    private void set(String section, String key, Object value) {
        cfg.set(path(section, key), value);
    }

    private static String path(String section, String key) {
        return section + "." + key;
    }

    private static String normalise(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.equalsIgnoreCase("null") || trimmed.equalsIgnoreCase("none")
                ? "" : trimmed;
    }
}
