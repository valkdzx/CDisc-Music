package dev.valkdz.cdisc.audio;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Config;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class LoadDiagnosis {

    private LoadDiagnosis() {
    }

    public static String explain(Main plugin, Player player, String query) {
        if (query == null || query.isBlank()) return null;
        Config config = plugin.cdiscConfig();
        String q = query.trim();
        String lower = q.toLowerCase(Locale.ROOT);

        if (LocalMusicLibrary.isLocalQuery(q)) {
            LocalMusicLibrary library = plugin.getLocalMusic();
            if (library == null || !library.isEnabled()) {
                return msg(plugin, player, "diagnose.local_off");
            }
            if (library.index().isEmpty()) {
                return msg(plugin, player, "diagnose.local_empty", library.root().toString());
            }
            return null;
        }

        if (lower.contains("open.spotify.com") || lower.startsWith("spotify:")) {
            return spotifyHint(plugin, player, config);
        }

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            boolean youtube = lower.contains("youtube.com") || lower.contains("youtu.be");
            if (youtube) return youtubeHint(plugin, player, config);
            if (lower.contains("twitch.tv")) {
                return config.isTwitchEnabled() ? null : msg(plugin, player, "diagnose.source_off", "twitch");
            }
            if (DiscordSource.isDiscordUrl(q)) {
                if (!config.isDiscordEnabled() && !config.isHttpEnabled()) {
                    return msg(plugin, player, "diagnose.source_off", "discord");
                }

                return DiscordSource.hasExpired(q)
                        ? msg(plugin, player, "diagnose.discord_expired") : null;
            }
            if (!config.isHttpEnabled()) {
                return msg(plugin, player, "diagnose.http_off");
            }
            return null;
        }

        if (lower.startsWith("sp:")) {
            return keyed(plugin, player, config.isSpotifyEnabled(),
                    has(config.getSpotifyClientId()) && has(config.getSpotifyClientSecret()),
                    "spotify");
        }
        if (lower.startsWith("ym:")) {
            return keyed(plugin, player, config.isYandexMusicEnabled(),
                    has(config.getYandexMusicAccessToken()), "yandex-music");
        }
        if (lower.startsWith("vk:")) {
            return keyed(plugin, player, config.isVkMusicEnabled(),
                    has(config.getVkMusicUserToken()), "vk-music");
        }
        if (lower.startsWith("sc:")) {
            return config.isSoundcloudEnabled() ? null : msg(plugin, player, "diagnose.source_off", "soundcloud");
        }
        if (lower.startsWith("tt:")) {
            return config.isTiktokEnabled() ? null : msg(plugin, player, "diagnose.source_off", "tiktok");
        }

        return youtubeHint(plugin, player, config);
    }

    private static String youtubeHint(Main plugin, Player player, Config config) {
        if (!config.isYoutubeEnabled()) {
            return msg(plugin, player, "diagnose.source_off", "youtube");
        }
        boolean poToken = has(config.getPOtoken()) && has(config.getVisitorData());
        boolean backend = has(config.getPoTokenBackendUrl());
        if (poToken || backend) return null;
        return msg(plugin, player, "diagnose.youtube_po_token");
    }

    private static String spotifyHint(Main plugin, Player player, Config config) {
        if (!config.isSpotifyEnabled()) {
            return msg(plugin, player, "diagnose.source_off", "spotify");
        }

        boolean own = has(config.getSpotifyClientId()) && has(config.getSpotifyClientSecret());
        boolean backend = has(config.getPoTokenBackendUrl());

        return own || backend ? null : msg(plugin, player, "diagnose.spotify_unresolvable");
    }

    private static String keyed(Main plugin, Player player,
                                boolean enabled, boolean hasKey, String name) {
        if (!enabled) return msg(plugin, player, "diagnose.source_off", name);
        if (!hasKey) return msg(plugin, player, "diagnose.source_no_key", name);
        return null;
    }

    private static String msg(Main plugin, Player player, String path, Object... args) {
        return plugin.getMessageManager().get(player, path, args);
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }
}
