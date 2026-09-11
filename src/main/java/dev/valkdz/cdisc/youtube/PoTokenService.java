package dev.valkdz.cdisc.youtube;

import dev.lavalink.youtube.clients.Web;
import dev.valkdz.cdisc.Main;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PoTokenService {

    private static final long RETRY_TICKS = 5 * 60 * 20L;

    private static final double REFRESH_AT = 0.8;

    private static final long RENEWAL_TTL_SECONDS = 60 * 60;

    private static final long WATCHDOG_TICKS = 30 * 20L;

    private static final long TICKS_PER_SECOND = 20L;

    private final Main plugin;
    private final PoTokenBackend backend;
    private final VisitorRenewal renewal = new VisitorRenewal();
    private final TokenCache cache;

    private BukkitTask task;
    private BukkitTask watchdog;

    private volatile long seenStamp;

    private volatile int generation;

    private final Set<String> refused = ConcurrentHashMap.newKeySet();

    private static volatile PoTokenService current;

    public static void reportNotAttested() {
        PoTokenService service = current;
        if (service != null) service.identityRefused();
    }

    public PoTokenService(Main plugin) {
        this.plugin = plugin;
        this.backend = new PoTokenBackend("CDisc/" + plugin.getDescription().getVersion());
        this.cache = new TokenCache(plugin.getDataFolder());
    }

    public boolean isEnabled() {
        return !plugin.cdiscConfig().getPoTokenBackendUrl().isBlank();
    }

    public void start() {
        stop();
        current = this;

        TokenCache.Entry stored = cache.read();
        long remaining = stored != null && stored.isUsable() ? stored.remainingSeconds() : 0;

        if (stored != null && stored.isUsable()) {

            seenStamp = cache.lastModified();
            install(stored.poToken(), stored.visitorData());
        }

        if (remaining > 0) {
            plugin.getLogger().info("YouTube proof-of-origin pair restored from "
                    + TokenCache.FILE_NAME + "; renewing in " + (remaining / 60) + " min.");
            schedule((long) (remaining * REFRESH_AT) * TICKS_PER_SECOND);
        } else {

            schedule(20L);
        }

        startWatchdog();
    }

    public void stop() {
        if (current == this) current = null;
        generation++;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
    }

    private void identityRefused() {
        String identity = VisitorRenewal.identityOf(plugin.cdiscConfig().getVisitorData());
        if (identity == null || !refused.add(identity)) return;

        plugin.getLogger().warning("YouTube will not attest the visitor identity "
                + identity + " any more, so tracks play about a minute and stop. "
                + "It still renews perfectly well — the refusal shows up only "
                + "in playback — so it is struck off and another is being found.");

        hop(generation, () -> {
            if (task != null) task.cancel();
            schedule(20L);
        });
    }

    private void schedule(long delayTicks) {
        int gen = generation;
        task = Bukkit.getScheduler().runTaskLaterAsynchronously(
                plugin, () -> refresh(gen), Math.max(1L, delayTicks));
    }

    private void startWatchdog() {
        int gen = generation;
        watchdog = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (gen != generation || !plugin.isEnabled()) return;

            long stamp = cache.lastModified();
            if (stamp == 0 || stamp == seenStamp) return;

            TokenCache.Entry entry = cache.read();
            if (entry == null || !entry.isUsable()) return;

            seenStamp = stamp;
            hop(gen, () -> {
                install(entry.poToken(), entry.visitorData());
                plugin.getLogger().info("YouTube proof-of-origin pair reloaded: "
                        + TokenCache.FILE_NAME + " changed underneath us.");
            });
        }, WATCHDOG_TICKS, WATCHDOG_TICKS);
    }

    private void refresh(int gen) {
        if (gen != generation || !plugin.isEnabled()) return;

        int timeout = plugin.cdiscConfig().getPoTokenTimeoutSeconds();

        String chosen = usable(configuredIdentity());
        if (chosen == null) chosen = usable(cachedIdentity());

        if (chosen != null && renewed(gen, chosen, timeout)) return;

        if (isEnabled() && fetched(gen, timeout)) return;

        if (chosen == null && !isEnabled()) {
            plugin.getLogger().warning("No YouTube pair and no token server: "
                    + "tracks will play about a minute and stop. Either paste a pair "
                    + "into tokens.yml, or set youtube.po-token-backend.url in "
                    + "sources.yml to a server that can earn one.");
        }

        hop(gen, () -> schedule(RETRY_TICKS));
    }

    private boolean renewed(int gen, String identity, int timeout) {
        try {
            String visitorData = renewal.renew(identity, timeout);
            String token = poTokenFor(identity);
            hop(gen, () -> apply(gen, token, visitorData, RENEWAL_TTL_SECONDS, "renewal"));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception e) {

            plugin.getLogger().warning("Couldn't renew the YouTube visitor identity "
                    + identity + ": " + describe(e));
            return false;
        }
    }

    private boolean fetched(int gen, int timeout) {
        try {
            PoTokenBackend.Pair pair = backend.fetch(
                    plugin.cdiscConfig().getPoTokenBackendUrl(),
                    plugin.cdiscConfig().getPoTokenBackendPassword(),
                    timeout);

            hop(gen, () -> apply(gen, pair.poToken(), pair.visitorData(),
                    pair.expiresInSeconds(), "token server"));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Couldn't get a proof-of-origin pair from "
                    + plugin.cdiscConfig().getPoTokenBackendUrl() + ": " + describe(e));
            return false;
        }
    }

    private String configuredIdentity() {
        return VisitorRenewal.identityOf(
                plugin.cdiscConfig().tokens().youtubeVisitorData());
    }

    private String usable(String identity) {
        if (identity == null || identity.isBlank()) return null;
        return refused.contains(identity) ? null : identity;
    }

    private String cachedIdentity() {
        TokenCache.Entry stored = cache.read();
        return stored == null ? null : stored.visitorId();
    }

    private String poTokenFor(String identity) {
        TokenCache.Entry stored = cache.read();
        if (stored != null && identity.equals(stored.visitorId())
                && stored.poToken() != null) {
            return stored.poToken();
        }

        String configured = plugin.cdiscConfig().tokens().youtubePoToken();
        return !configured.isBlank() && identity.equals(configuredIdentity())
                ? configured
                : "";
    }

    private void apply(int gen, String poToken, String visitorData,
                       long expiresInSeconds, String source) {

        if (gen != generation || !plugin.isEnabled()) return;

        install(poToken, visitorData);

        plugin.cdiscConfig().tokens().setYoutubePoToken(poToken == null ? "" : poToken);
        plugin.cdiscConfig().tokens().setYoutubeVisitorData(visitorData);
        plugin.cdiscConfig().tokens().save();

        long ttl = expiresInSeconds > 0 ? expiresInSeconds : RENEWAL_TTL_SECONDS;
        try {
            cache.write(poToken, visitorData, ttl, source);
            seenStamp = cache.lastModified();
        } catch (Exception e) {

            plugin.getLogger().warning("Couldn't write " + TokenCache.FILE_NAME
                    + ": " + describe(e));
        }

        long nextSeconds = Math.max(60, (long) (ttl * REFRESH_AT));
        plugin.getLogger().info("YouTube proof-of-origin pair installed from " + source
                + "; renewing in " + (nextSeconds / 60) + " min.");

        schedule(nextSeconds * TICKS_PER_SECOND);
    }

    private void install(String poToken, String visitorData) {
        Web.setPoTokenAndVisitorData(
                poToken == null || poToken.isBlank() ? null : poToken,
                visitorData == null || visitorData.isBlank() ? null : visitorData);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.toString() : message;
    }

    private void hop(int gen, Runnable action) {
        if (gen != generation || !plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (IllegalStateException e) {

        }
    }
}
