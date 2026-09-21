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

        metrics.addCustomChart(new SingleLineChart("tracks_played",
                () -> TRACKS_PLAYED.getAndSet(0)));
        metrics.addCustomChart(new AdvancedPie("playback_sources",
                CDiscMetrics::drainSourcePlays));

        metrics.addCustomChart(new SimplePie("voice_backend", () -> {
            if (plugin.getVoiceBackendManager() == null) return "none";

            return plugin.getVoiceBackendManager().getMode();
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
