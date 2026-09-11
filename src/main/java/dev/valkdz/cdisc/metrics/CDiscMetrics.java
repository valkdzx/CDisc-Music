package dev.valkdz.cdisc.metrics;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Config;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class CDiscMetrics {

    private static final int SERVICE_ID = 32754;

    private static final AtomicInteger TRACKS_PLAYED = new AtomicInteger();

    private static final Map<String, AtomicInteger> SOURCE_PLAYS = new ConcurrentHashMap<>();

    private CDiscMetrics() {
    }

    public static void recordTrackPlayed(String sourceName) {
        TRACKS_PLAYED.incrementAndGet();
        String source = (sourceName == null || sourceName.isBlank()) ? "unknown" : sourceName;
        SOURCE_PLAYS.computeIfAbsent(source, key -> new AtomicInteger()).incrementAndGet();
    }

    public static Metrics start(Main plugin) {
        if (!plugin.getConfig().getBoolean("metrics", true)) {
            plugin.getLogger().info("bStats metrics are disabled in config.yml.");
            return null;
        }
        if (SERVICE_ID <= 0) {
            plugin.getLogger().warning("bStats service ID is not set — metrics will not be submitted.");
            return null;
        }

        Metrics metrics = new Metrics(plugin, SERVICE_ID);
        Config cfg = plugin.cdiscConfig();

        metrics.addCustomChart(new SingleLineChart("tracks_played",
                () -> TRACKS_PLAYED.getAndSet(0)));
        metrics.addCustomChart(new AdvancedPie("playback_sources",
                CDiscMetrics::drainSourcePlays));

        metrics.addCustomChart(new SimplePie("voice_backend", () -> {
            if (plugin.getVoiceBackendManager() == null) return "none";

            return plugin.getVoiceBackendManager().getMode();
        }));

        metrics.addCustomChart(new SimplePie("youtube_fallback_api",
                () -> String.valueOf(cfg.getYoutubeCustomApi())));
        metrics.addCustomChart(new SimplePie("youtube_oauth",
                () -> String.valueOf(cfg.isYoutubeOauthEnabled())));
        metrics.addCustomChart(new SimplePie("fast_create",
                () -> String.valueOf(cfg.isYoutubeFastCreate())));
        metrics.addCustomChart(new SimplePie("update_checker",
                () -> String.valueOf(cfg.isUpdateCheckerEnabled())));

        metrics.addCustomChart(new AdvancedPie("enabled_sources", () -> {
            Map<String, Integer> values = new HashMap<>();
            if (cfg.isYoutubeEnabled()) values.put("YouTube", 1);
            if (cfg.isSoundcloudEnabled()) values.put("SoundCloud", 1);
            if (cfg.isSpotifyEnabled()) values.put("Spotify", 1);
            if (cfg.isYandexMusicEnabled()) values.put("Yandex Music", 1);
            if (cfg.isVkMusicEnabled()) values.put("VK Music", 1);
            if (cfg.isTwitchEnabled()) values.put("Twitch", 1);
            if (cfg.isMixcloudEnabled()) values.put("Mixcloud", 1);
            if (cfg.isTiktokEnabled()) values.put("TikTok", 1);
            if (cfg.isRedditEnabled()) values.put("Reddit", 1);
            if (cfg.isVimeoEnabled()) values.put("Vimeo", 1);
            if (cfg.isOcremixEnabled()) values.put("OCReMix", 1);
            if (cfg.isBandcampEnabled()) values.put("Bandcamp", 1);
            if (cfg.isHttpEnabled()) values.put("HTTP", 1);
            return values;
        }));

        return metrics;
    }

    private static Map<String, Integer> drainSourcePlays() {
        Map<String, Integer> snapshot = new HashMap<>();
        for (Map.Entry<String, AtomicInteger> entry : SOURCE_PLAYS.entrySet()) {
            int count = entry.getValue().getAndSet(0);
            if (count > 0) snapshot.put(entry.getKey(), count);
        }
        return snapshot;
    }
}
