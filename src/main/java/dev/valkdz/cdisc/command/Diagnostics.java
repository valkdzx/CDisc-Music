package dev.valkdz.cdisc.command;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LocalMusicLibrary;
import dev.valkdz.cdisc.util.Config;

import java.util.ArrayList;
import java.util.List;

final class Diagnostics {

    private enum State {
        READY("&a✔"),
        OFF("&8✖"),
        NEEDS_KEY("&e!"),
        FRAGILE("&6?");

        final String symbol;

        State(String symbol) {
            this.symbol = symbol;
        }
    }

    private Diagnostics() {
    }

    static List<String> report(Main plugin) {
        Config config = plugin.cdiscConfig();
        List<String> out = new ArrayList<>();

        out.add("&6CDisc " + plugin.getDescription().getVersion() + " &7— what works right now");
        out.add("");

        out.add("&fServer's own files");
        LocalMusicLibrary library = plugin.getLocalMusic();
        if (library != null && library.isEnabled()) {
            int count = library.index().size();
            out.add(line(State.READY, "local:", count + " file(s) in " + library.root()));
            if (count == 0) {
                out.add("  &7Drop an .mp3 in that folder and it's playable at once —");
                out.add("  &7no keys, no account, nothing to configure.");
            }
        } else {
            out.add(line(State.OFF, "local:", "switched off in sources.yml"));
        }

        if (config.isDownloadEnabled()) {
            long mb = config.getDownloadMaxBytes() / (1024L * 1024L);
            out.add(line(State.READY, "/cdisc download", "up to " + mb + " MB per file"));
        } else {
            out.add(line(State.OFF, "/cdisc download", "switched off in sources.yml"));
        }

        String proxy = dev.valkdz.cdisc.util.NetProxy.address();
        if (proxy != null) {
            out.add("");
            out.add(line(State.READY, "proxy", "every request goes out through " + proxy));
        }

        out.add("");
        out.add("&fOnline sources");
        out.add(source(config.isSoundcloudEnabled(), true, "soundcloud", "sc:", null));
        if (config.isSpotifyEnabled() && config.isYoutubeEnabled()
                && !(has(config.getSpotifyClientId()) && has(config.getSpotifyClientSecret()))) {
            out.add(line(State.READY, "spotify (sp:)", "no key — searches YouTube instead"));
        } else {
            out.add(source(config.isSpotifyEnabled(),
                    has(config.getSpotifyClientId()) && has(config.getSpotifyClientSecret()),
                    "spotify", "sp:", "client-id and client-secret in tokens.yml"));
        }
        out.add(source(config.isYandexMusicEnabled(), has(config.getYandexMusicAccessToken()),
                "yandex-music", "ym:", "access-token in tokens.yml"));
        out.add(source(config.isVkMusicEnabled(), has(config.getVkMusicUserToken()),
                "vk-music", "vk:", "user-token in tokens.yml"));
        out.add(source(config.isMixcloudEnabled(), true, "mixcloud", null, null));
        out.add(source(config.isTiktokEnabled(), true, "tiktok", "tt:", null));
        out.add(source(config.isRedditEnabled(), true, "reddit", null, null));
        out.add(source(config.isTwitchEnabled(), true, "twitch", null, null));
        out.add(source(config.isDiscordEnabled(), true, "discord", null, null));

        if (config.isHttpEnabled()) {
            out.add(line(State.READY, "http", "any direct link to an audio file"));
        } else {
            out.add(line(State.OFF, "http", "off — /cdisc download is the safer way in"));
        }

        out.add("");
        out.add("&fYouTube");
        if (!config.isYoutubeEnabled()) {
            out.add(line(State.OFF, "youtube", "switched off in sources.yml"));
        } else {
            boolean poToken = has(config.getPOtoken()) && has(config.getVisitorData());
            boolean backend = has(config.getPoTokenBackendUrl());

            if (backend) {
                out.add(line(State.READY, "po-token", "fetched automatically from "
                        + config.getPoTokenBackendUrl()));
            } else if (poToken) {
                out.add(line(State.READY, "po-token", "pasted by hand — goes stale in a few weeks"));
            } else {
                out.add(line(State.FRAGILE, "po-token", "none"));
                out.add("  &7YouTube answers a server with no po-token with a bot check");
                out.add("  &7more often than not. If yt: fails and nothing else does,");
                out.add("  &7this is why.");
            }

            if (config.getYoutubeCustomApi()) {
                out.add(line(State.READY, "fallback-api",
                        "asking through the backend — no keys needed"));

                if (!config.isYoutubeProxyEnabled()) {
                    out.add(line(State.FRAGILE, "proxy",
                            "off — turn it on unless the backend runs on this machine"));
                    out.add("  &7The backend hands back a link bound to its own address.");
                    out.add("  &7Fetching it from here answers 403, and does so only");
                    out.add("  &7sometimes, which is what makes it hard to place.");
                } else {
                    out.add(line(State.READY, "proxy", "audio comes through the backend"));
                }
            } else {
                out.add(line(State.FRAGILE, "fallback-api",
                        "off — turn it on first if YouTube won't play"));
            }

            if (!config.isYoutubeSabrEnabled()) {
                out.add(line(State.FRAGILE, "sabr",
                        "off — a SABR-only answer will play nothing"));
                out.add("  &7YouTube has started sending responses that list every");
                out.add("  &7format and link to none of them. Set youtube.sabr to read");
                out.add("  &7those here instead of treating them as a dead track.");
            } else if (poToken) {
                out.add(line(State.READY, "sabr",
                        "ready — SABR-only answers are read directly"));
            } else {
                out.add(line(State.FRAGILE, "sabr",
                        "on, but it has no po-token to present"));
                out.add("  &7SABR checks a proof of origin on every request, so it");
                out.add("  &7needs po-token and visitor-data in tokens.yml. Without");
                out.add("  &7them this path is on but cannot be used.");
            }

            out.add(line(State.READY, "clients",
                    String.join(", ", config.getYoutubeClients())));
            if (!config.isYoutubeClientFailureLogging()) {
                out.add("  &7Set youtube.log-client-failures to see why each one fails.");
            }
        }

        out.add("");
        out.add("&fLyrics");
        if (!config.isLyricsEnabled()) {
            out.add(line(State.OFF, "lyrics", "switched off in config.yml"));
        } else {
            out.add(line(State.READY, "databases",
                    String.join(", ", config.getLyricsProviders()) + " — free, no key"));
            out.add(line(State.READY, "songs cached",
                    plugin.getLyricsService().cachedCount() + " looked up since startup"));

            var drawn = plugin.getLyricsDisplay().drawn();
            out.add(line(State.READY, "holograms",
                    drawn.total() + " floating, read by " + drawn.watching() + " player(s)"));
            out.add(line(State.READY, "presets",
                    plugin.getHologramPresets().size() + " player(s) with a look of their own"));

            if (!config.isLyricsDefaultOn()) {
                out.add("  &7Off on a jukebox nobody has asked — the paper in its GUI");
                out.add("  &7turns it on, and right-clicking it opens your own look.");
            }
        }

        out.add("");
        out.add("&fVoice");
        boolean voiceReady = plugin.getServer().getPluginManager().isPluginEnabled("voicechat")
                || plugin.getServer().getPluginManager().isPluginEnabled("PlasmoVoice");
        String backend = plugin.getVoiceBackendManager() == null
                ? "none"
                : plugin.getVoiceBackendManager().getMode();
        out.add(line(voiceReady ? State.READY : State.NEEDS_KEY, "backend",
                voiceReady ? backend
                        : "no voice plugin found — nothing will be audible"));
        if ("multi".equals(backend) && plugin.getVoiceBackendManager().getBackend() != null) {
            out.add("  &7Broadcasting through " + plugin.getVoiceBackendManager().getBackend().name()
                    + ", so players");
            out.add("  &7on either mod hear it, and players on both hear it once.");
            out.add("  &7Name one in config.yml to use just that plugin.");
        }

        out.add("");
        out.add("&7A ✔ means it should work. A ! means it's on but unusable until");
        out.add("&7something is filled in. A ? means it works until it doesn't.");
        return out;
    }

    private static String source(boolean enabled, boolean keyed,
                                 String name, String prefix, String needs) {
        String label = prefix == null ? name : name + " (" + prefix + ")";
        if (!enabled) return line(State.OFF, label, "switched off in sources.yml");
        if (!keyed) return line(State.NEEDS_KEY, label, "needs " + needs);

        return line(State.READY, label, needs == null ? "no key needed" : "key in tokens.yml");
    }

    private static String line(State state, String label, String detail) {
        return state.symbol + " &f" + label + " &7" + detail;
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }
}
