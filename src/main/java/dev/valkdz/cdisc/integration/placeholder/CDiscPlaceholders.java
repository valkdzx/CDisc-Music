package dev.valkdz.cdisc.integration.placeholder;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.api.CDiscApi;
import dev.valkdz.cdisc.api.NowPlaying;
import dev.valkdz.cdisc.util.TimeUtils;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;

final class CDiscPlaceholders extends PlaceholderExpansion {

    private static final List<String> NAMES = List.of(
            "jukeboxes", "listening", "playing", "paused", "live", "title", "author", "track",
            "uri", "position", "duration", "remaining", "progress");

    private final Main plugin;

    CDiscPlaceholders(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "cdisc";
    }

    @Override
    public String getAuthor() {
        return "valkdz";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public List<String> getPlaceholders() {
        return NAMES.stream().map(name -> "%cdisc_" + name + "%").toList();
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        String key = params.toLowerCase(java.util.Locale.ROOT);
        if (key.equals("jukeboxes")) return String.valueOf(CDiscApi.playingJukeboxes().size());
        if (!NAMES.contains(key)) return null;

        Player player = offline == null ? null : offline.getPlayer();
        NowPlaying now = player == null ? null : CDiscApi.audibleTo(player).orElse(null);
        return now == null ? idle(key) : value(key, now);
    }

    private static String idle(String key) {
        return switch (key) {
            case "listening", "playing", "paused", "live" -> PlaceholderAPIPlugin.booleanFalse();
            case "progress" -> "0";
            default -> "";
        };
    }

    private static String value(String key, NowPlaying now) {
        boolean timed = !now.live() && now.durationMs() > 0 && now.durationMs() != Long.MAX_VALUE;
        return switch (key) {
            case "listening" -> PlaceholderAPIPlugin.booleanTrue();
            case "playing" -> bool(!now.paused());
            case "paused" -> bool(now.paused());
            case "live" -> bool(now.live());
            case "title" -> text(now.title());
            case "author" -> text(now.author());
            case "track" -> now.author() == null || now.author().isBlank()
                    ? text(now.title()) : now.author() + " - " + text(now.title());
            case "uri" -> text(now.uri());
            case "position" -> TimeUtils.format(now.positionMs());
            case "duration" -> timed ? TimeUtils.format(now.durationMs()) : "";
            case "remaining" -> timed ? TimeUtils.format(Math.max(0, now.durationMs() - now.positionMs())) : "";
            case "progress" -> timed
                    ? String.valueOf(Math.min(100, now.positionMs() * 100 / now.durationMs())) : "0";
            default -> null;
        };
    }

    private static String bool(boolean value) {
        return value ? PlaceholderAPIPlugin.booleanTrue() : PlaceholderAPIPlugin.booleanFalse();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
