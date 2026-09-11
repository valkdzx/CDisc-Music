package dev.valkdz.cdisc.util;

import dev.valkdz.cdisc.Main;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Logger;

final class ConfigSplitMigration {

    private static final List<String> MOVED_SECTIONS =
            List.of("sources", "sources-config", "remote-cipher-server");

    private static final String BACKUP_NAME = "config-before-split.yml.bak";

    private ConfigSplitMigration() {
    }

    static void run(Main plugin, Tokens tokens, SourcesConfig sources) {
        FileConfiguration old = plugin.getConfig();
        if (MOVED_SECTIONS.stream().noneMatch(old::isConfigurationSection)) {
            return;
        }

        Logger log = plugin.getLogger();
        log.info("Splitting the old config.yml: credentials -> " + Tokens.FILE_NAME
                + ", source settings -> " + SourcesConfig.FILE_NAME + ".");

        backUp(plugin, log);
        int secrets = moveSecrets(old, tokens);
        moveSourceSettings(old, sources);

        tokens.save();
        sources.save();

        for (String section : MOVED_SECTIONS) {
            old.set(section, null);
        }
        plugin.saveConfig();

        log.info("Moved " + secrets + " credential(s) into " + Tokens.FILE_NAME
                + ". The old config.yml is kept as " + BACKUP_NAME
                + " — delete it once you've checked everything still works,"
                + " it still holds those credentials.");
    }

    private static void backUp(Main plugin, Logger log) {
        File source = new File(plugin.getDataFolder(), "config.yml");
        File backup = new File(plugin.getDataFolder(), BACKUP_NAME);
        if (!source.exists() || backup.exists()) return;

        try {
            Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warning("Couldn't back up config.yml before splitting it: " + e.getMessage());
        }
    }

    private static int moveSecrets(FileConfiguration old, Tokens tokens) {
        int moved = 0;

        String clientId = trimmed(old, "sources-config.spotify.clientid");
        String clientSecret = trimmed(old, "sources-config.spotify.clientsecret");
        if (!clientId.isEmpty() || !clientSecret.isEmpty()) {
            tokens.setSpotify(clientId, clientSecret);
            moved += 2;
        }

        moved += carry(trimmed(old, "sources-config.yandex-music.access-token"),
                tokens::setYandexAccessToken);
        moved += carry(trimmed(old, "sources-config.vk-music.user-token"),
                tokens::setVkUserToken);
        moved += carry(trimmed(old, "sources-config.youtube.oauth.refreshToken"),
                tokens::setYoutubeRefreshToken);
        moved += carry(trimmed(old, "sources-config.youtube.oauth.po-token"),
                tokens::setYoutubePoToken);
        moved += carry(trimmed(old, "sources-config.youtube.oauth.visitor-data"),
                tokens::setYoutubeVisitorData);
        moved += carry(trimmed(old, "remote-cipher-server.password"),
                tokens::setCipherPassword);

        if (old.isSet("sources-config.youtube.oauth.enabled")) {
            tokens.setYoutubeOauthEnabled(
                    old.getBoolean("sources-config.youtube.oauth.enabled", false));
        }
        if (old.isSet("sources-config.youtube.oauth.setup_done")) {
            tokens.setYoutubeOauthSetupDone(
                    String.valueOf(old.get("sources-config.youtube.oauth.setup_done")));
        }
        return moved;
    }

    private static void moveSourceSettings(FileConfiguration old, SourcesConfig sources) {
        ConfigurationSection enabled = old.getConfigurationSection("sources");
        if (enabled != null) {
            for (String key : enabled.getKeys(false)) {
                sources.raw().set("enabled." + key, enabled.getBoolean(key));
            }
        }

        copyIfSet(old, "sources-config.youtube.fallback-api", sources, "youtube.fallback-api");
        copyIfSet(old, "sources-config.youtube.proxy", sources, "youtube.proxy");
        copyIfSet(old, "sources-config.youtube.fast-create", sources, "youtube.fast-create");
        copyIfSet(old, "sources-config.youtube.probe-timeout-seconds", sources,
                "youtube.probe-timeout-seconds");
        copyIfSet(old, "remote-cipher-server.url", sources, "youtube.remote-cipher.url");
    }

    private static void copyIfSet(FileConfiguration old, String from,
                                  SourcesConfig sources, String to) {
        if (old.isSet(from)) {
            sources.raw().set(to, old.get(from));
        }
    }

    private static int carry(String value, java.util.function.Consumer<String> setter) {
        if (value.isEmpty()) return 0;
        setter.accept(value);
        return 1;
    }

    private static String trimmed(FileConfiguration cfg, String path) {
        String value = cfg.getString(path, "");
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.equalsIgnoreCase("null") ? "" : trimmed;
    }
}
