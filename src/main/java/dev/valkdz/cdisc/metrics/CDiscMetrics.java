package dev.valkdz.cdisc.metrics;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Config;
import dev.valkdz.cdisc.util.Tasks;
import org.bstats.MetricsBase;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bstats.config.MetricsConfig;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

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

    public static void start(Main plugin) {
        if (!plugin.getConfig().getBoolean("metrics", true)) {
            plugin.getLogger().info("bStats metrics are disabled in config.yml.");
            return;
        }
        if (SERVICE_ID <= 0) {
            plugin.getLogger().warning("bStats service ID is not set — metrics will not be submitted.");
            return;
        }

        if (Tasks.isFolia()) {
            startOnFolia(plugin);
            return;
        }

        Metrics metrics = new Metrics(plugin, SERVICE_ID);
        addCharts(plugin, metrics::addCustomChart);
    }

    // bStats' own Metrics hands each submission to the Bukkit scheduler, which Folia
    // does not run: same set-up, global region scheduler instead.
    private static void startOnFolia(Main plugin) {
        MetricsConfig config;
        try {
            File folder = new File(plugin.getDataFolder().getParentFile(), "bStats");
            config = new MetricsConfig(new File(folder, "config.yml"), true);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the bStats config: " + e.getMessage());
            return;
        }

        MetricsBase base = new MetricsBase(
                "bukkit",
                config.getServerUUID(),
                SERVICE_ID,
                config.isEnabled(),
                CDiscMetrics::appendPlatformData,
                builder -> builder.appendField("pluginVersion",
                        plugin.getDescription().getVersion()),
                task -> Tasks.global(plugin, task),
                plugin::isEnabled,
                (message, error) -> plugin.getLogger().log(Level.WARNING, message, error),
                message -> plugin.getLogger().log(Level.INFO, message),
                config.isLogErrorsEnabled(),
                config.isLogSentDataEnabled(),
                config.isLogResponseStatusTextEnabled(),
                false);

        addCharts(plugin, base::addCustomChart);
    }

    private static void appendPlatformData(JsonObjectBuilder builder) {
        builder.appendField("playerAmount", Bukkit.getOnlinePlayers().size());
        builder.appendField("onlineMode", Bukkit.getOnlineMode() ? 1 : 0);
        builder.appendField("bukkitVersion", Bukkit.getVersion());
        builder.appendField("bukkitName", Bukkit.getName());
        builder.appendField("javaVersion", System.getProperty("java.version"));
        builder.appendField("osName", System.getProperty("os.name"));
        builder.appendField("osArch", System.getProperty("os.arch"));
        builder.appendField("osVersion", System.getProperty("os.version"));
        builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }

    private static void addCharts(Main plugin, Consumer<CustomChart> metrics) {
        metrics.accept(new SingleLineChart("tracks_played",
                () -> TRACKS_PLAYED.getAndSet(0)));
        metrics.accept(new AdvancedPie("playback_sources",
                CDiscMetrics::drainSourcePlays));

        metrics.accept(new SimplePie("voice_backend", () -> {
            if (plugin.getVoiceBackendManager() == null) return "none";

            return plugin.getVoiceBackendManager().getMode();
        }));
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
