package dev.valkdz.cdisc.lyrics;

import dev.valkdz.cdisc.Main;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

public final class LyricsService {

    private static final Duration HIT_TTL = Duration.ofHours(6);

    private static final Duration MISS_TTL = Duration.ofMinutes(30);

    private static final Duration ERROR_TTL = Duration.ofMinutes(2);

    private static final int MAX_ENTRIES = 256;

    public enum State {

        SEARCHING,

        FOUND,

        MISSING
    }

    public record Result(State state, SyncedLyrics lyrics) {

        private static final Result SEARCHING = new Result(State.SEARCHING, null);
        private static final Result MISSING = new Result(State.MISSING, null);

        public boolean isFound() {
            return state == State.FOUND && lyrics != null;
        }
    }

    private static final class Entry {
        volatile State state = State.SEARCHING;
        volatile SyncedLyrics lyrics;

        volatile long expiresAt;

        volatile long touchedAt = System.currentTimeMillis();
    }

    private final Main plugin;

    private final List<LyricsProvider> providers;

    private final ExecutorService workers;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public LyricsService(Main plugin) {
        this.plugin = plugin;
        this.providers = buildProviders(plugin);

        this.workers = Executors.newFixedThreadPool(2, daemonThreads());
    }

    private static List<LyricsProvider> buildProviders(Main plugin) {
        IntSupplier timeout = () -> plugin.cdiscConfig().getLyricsTimeoutSeconds();
        String userAgent = "CDisc/" + plugin.getDescription().getVersion()
                + " (Minecraft plugin; https://modrinth.com/plugin/cdisc-music)";

        List<LyricsProvider> built = new ArrayList<>();
        for (String name : plugin.cdiscConfig().getLyricsProviders()) {
            switch (name.toLowerCase(Locale.ROOT)) {
                case "lrclib" -> built.add(new LrcLibProvider(userAgent, timeout));
                case "netease" -> built.add(new NetEaseProvider(timeout));
                default -> plugin.getLogger().warning(
                        "Unknown lyrics provider '" + name + "' in config.yml — skipping it. "
                                + "Known providers: lrclib, netease.");
            }
        }
        return List.copyOf(built);
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "CDisc-Lyrics-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public Result lookup(LyricsQuery query) {
        if (query == null || !query.isUsable()) return Result.MISSING;

        String key = query.cacheKey();
        Entry entry = cache.get(key);
        long now = System.currentTimeMillis();

        if (entry != null) {
            entry.touchedAt = now;

            if (entry.expiresAt == 0 || entry.expiresAt > now) {
                return switch (entry.state) {
                    case FOUND -> new Result(State.FOUND, entry.lyrics);
                    case MISSING -> Result.MISSING;
                    case SEARCHING -> Result.SEARCHING;
                };
            }
            cache.remove(key, entry);
        }

        Entry fresh = new Entry();
        if (cache.putIfAbsent(key, fresh) != null) {

            return Result.SEARCHING;
        }

        prune();
        submit(key, fresh, query);
        return Result.SEARCHING;
    }

    private void submit(String key, Entry entry, LyricsQuery query) {
        try {
            workers.execute(() -> {
                boolean anyBroke = false;

                for (LyricsProvider provider : providers) {
                    try {
                        SyncedLyrics lyrics = provider.fetch(query);
                        if (lyrics == null) continue;

                        entry.lyrics = lyrics;
                        entry.state = State.FOUND;
                        entry.expiresAt = System.currentTimeMillis() + HIT_TTL.toMillis();
                        return;
                    } catch (Exception e) {

                        anyBroke = true;
                        if (plugin.cdiscConfig().isLyricsDebug()) {
                            plugin.getLogger().warning("Lyrics lookup failed for \"" + query.artist()
                                    + " - " + query.track() + "\" via " + provider.id() + ": " + e);
                        }
                    }
                }

                entry.state = State.MISSING;
                entry.expiresAt = System.currentTimeMillis()
                        + (anyBroke ? ERROR_TTL : MISS_TTL).toMillis();
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {

            cache.remove(key, entry);
        }
    }

    private void prune() {
        if (cache.size() <= MAX_ENTRIES) return;

        List<Map.Entry<String, Entry>> oldest = cache.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().touchedAt))
                .limit(Math.max(1, cache.size() - MAX_ENTRIES))
                .toList();

        for (Map.Entry<String, Entry> entry : oldest) {
            // Never evict a fetch still in flight: its thread would write into an entry
            // nothing can read any more, and the lookup would repeat.
            if (entry.getValue().expiresAt == 0) continue;
            cache.remove(entry.getKey(), entry.getValue());
        }
    }

    public int cachedCount() {
        return cache.size();
    }

    public void clearCache() {
        cache.clear();
    }

    public void shutdown() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cache.clear();
    }
}
