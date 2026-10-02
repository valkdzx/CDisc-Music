package dev.valkdz.cdisc.update;

import dev.valkdz.cdisc.util.Json;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Chat;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateChecker implements Listener {

    private static final String PROJECT_ID = "uQjcky5I";
    private static final String VERSIONS_API =
            "https://api.modrinth.com/v2/project/" + PROJECT_ID + "/version";
    private static final String VERSION_PAGE =
            "https://modrinth.com/plugin/cdisc-music/version/";
    private static final String GITHUB_LATEST =
            "https://api.github.com/repos/valkdzx/CDisc-Music/releases/latest";

    private static final Set<String> BUKKIT_LOADERS =
            Set.of("bukkit", "spigot", "paper", "purpur", "folia");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final Main plugin;
    private final HttpClient http;
    private final PluginUpdater updater;

    private volatile String latestVersion;

    private volatile String latestPage;

    private volatile String announcedVersion;

    private volatile String stagedVersion;

    private volatile boolean modrinthMissing;

    private Tasks.Handle task;

    public UpdateChecker(Main plugin) {
        this.plugin = plugin;
        this.http = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.updater = new PluginUpdater(plugin, http);
    }

    public boolean isUpdateAvailable() {
        return latestVersion != null;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getDownloadUrl() {
        return latestVersion == null ? null : latestPage;
    }

    public void check() {
        stop();
        if (!plugin.cdiscConfig().isUpdateCheckerEnabled()) return;

        modrinthMissing = false;
        long periodTicks = plugin.cdiscConfig().getUpdateIntervalHours() * 72_000L;

        if (periodTicks <= 0) {
            task = Tasks.async(plugin, this::runCheck);
        } else {

            task = Tasks.asyncTimer(plugin, this::runCheck, 100L, periodTicks);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void shutdown() {
        stop();
        updater.onDisable(plugin.cdiscConfig().isAutoUpdateEnabled());
    }

    private void runCheck() {
        String current = plugin.getDescription().getVersion();
        String serverVersion = serverMinecraftVersion();
        stagedVersion = updater.stagedVersion();

        Release newest = newer(fromModrinth(), fromGitHub());
        if (newest == null) return;

        if (compareVersions(newest.version(), current) <= 0) {
            latestVersion = null;

            if (announcedVersion == null) {
                announcedVersion = current;
                plugin.getLogger().info("Up to date (running " + current
                        + ", newest on " + newest.where() + " " + newest.version() + ").");
            }
            return;
        }

        boolean compatible = newest.supports(serverVersion);
        boolean autoUpdate = plugin.cdiscConfig().isAutoUpdateEnabled();
        if (autoUpdate && compatible && newest.download() != null
                && !newest.version().equals(stagedVersion)) {
            try {
                updater.stage(newest.version(), newest.download());
                stagedVersion = newest.version();
            } catch (Exception e) {
                plugin.getLogger().warning("Auto-update: downloading " + newest.version()
                        + " failed, will try again on the next check: " + e);
            }
        }

        latestPage = newest.page();
        latestVersion = newest.version();
        boolean staged = newest.version().equals(stagedVersion);
        String announcement = newest.version() + (staged ? "+staged" : "");
        if (announcement.equals(announcedVersion)) return;
        announcedVersion = announcement;

        plugin.getLogger().warning("A new version is available: " + newest.version()
                + " (running " + current + ", server " + serverVersion + ")");
        if (staged) {
            plugin.getLogger().warning("Downloaded " + newest.download().fileName()
                    + ", it replaces this version when the server stops. Changes: " + newest.page());
        } else {
            plugin.getLogger().warning("Download: " + newest.page());
        }
        if (!compatible) {
            plugin.getLogger().warning("Note: " + newest.version()
                    + " does not list Minecraft " + serverVersion
                    + " among its tested versions — check the release page before updating."
                    + (autoUpdate ? " It was not downloaded automatically." : ""));
        }

        Tasks.global(plugin, () -> {
            if (!plugin.cdiscConfig().isUpdateNotifyOps()) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOp()) Tasks.onEntity(plugin, p, () -> notifyPlayer(p));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!isUpdateAvailable()) return;
        if (!plugin.cdiscConfig().isUpdateNotifyOps()) return;

        Player player = event.getPlayer();
        if (!player.isOp()) return;

        Tasks.entityLater(plugin, player, () -> {
            if (player.isOnline()) notifyPlayer(player);
        }, 40L);
    }

    private void notifyPlayer(Player player) {
        String version = latestVersion;
        if (version == null) return;

        String url = latestPage;
        boolean staged = version.equals(stagedVersion);
        String line = plugin.getMessageManager().get(player,
                staged ? "update.downloaded" : "update.available",
                version, plugin.getDescription().getVersion());
        String hover = plugin.getMessageManager().get(player,
                staged ? "update.downloaded_hover" : "update.hover");

        Chat.send(player, Chat.link(line, url, hover));
    }

    record Release(String version, String where, String page,
                           List<String> gameVersions, PluginUpdater.Download download) {
        boolean supports(String serverVersion) {
            if (gameVersions.isEmpty()) return true;
            for (String listed : gameVersions) {
                if (sameFamily(listed, serverVersion)) return true;
            }
            return false;
        }
    }

    private static boolean sameFamily(String a, String b) {
        if (a.equals(b)) return true;

        int[] left = numericParts(a);
        int[] right = numericParts(b);
        if (left.length < 2 || right.length < 2) return false;
        return left[0] == right[0] && left[1] == right[1];
    }

    private static Release newer(Release a, Release b) {
        if (a == null) return b;
        if (b == null) return a;
        return compareVersions(b.version(), a.version()) > 0 ? b : a;
    }

    private Release fromModrinth() {
        if (modrinthMissing) return null;
        try {
            return fetchModrinth();
        } catch (Exception e) {
            plugin.getLogger().warning("Update check on Modrinth failed: " + e);
            return null;
        }
    }

    private Release fetchModrinth() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(VERSIONS_API))
                .timeout(Duration.ofSeconds(10))

                .header("User-Agent", "valkdz/CDisc/" + plugin.getDescription().getVersion()
                        + " (modrinth update check)")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 404) {
            modrinthMissing = true;
            plugin.getLogger().warning("Update check: Modrinth has no project "
                    + PROJECT_ID + " (HTTP 404). Only GitHub is asked until restart.");
            return null;
        }
        if (status == 429) {
            plugin.getLogger().warning("Update check: rate limited by Modrinth (HTTP 429),"
                    + " will try again on the next check.");
            return null;
        }
        if (status != 200) {
            plugin.getLogger().warning("Update check: Modrinth returned HTTP " + status);
            return null;
        }

        Json root = Json.parse(response.body());
        if (root == null || root.isNull()) {
            plugin.getLogger().warning("Update check: Modrinth returned no version list.");
            return null;
        }

        Release best = null;
        int read = 0;
        for (Json entry : root.values()) {
            read++;

            if (!"release".equals(entry.get("version_type").text())) continue;
            if (!runsOnBukkit(entry)) continue;

            String number = entry.get("version_number").text();
            if (number == null || number.isBlank()) continue;

            if (best == null || compareVersions(number, best.version()) > 0) {
                best = new Release(number, "Modrinth", VERSION_PAGE + number,
                        textList(entry.get("game_versions")), primaryJar(entry));
            }
        }

        if (best == null) {
            plugin.getLogger().warning("Update check: Modrinth listed no releases for a"
                    + " Bukkit-family server (" + read + " versions read).");
        }
        return best;
    }

    private Release fromGitHub() {
        try {
            return fetchGitHub();
        } catch (Exception e) {
            plugin.getLogger().warning("Update check on GitHub failed: " + e);
            return null;
        }
    }

    private Release fetchGitHub() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(GITHUB_LATEST))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "valkdz/CDisc/" + plugin.getDescription().getVersion()
                        + " (update check)")
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 404) return null;
        if (status == 403 || status == 429) {
            plugin.getLogger().warning("Update check: rate limited by GitHub (HTTP " + status
                    + "), will try again on the next check.");
            return null;
        }
        if (status != 200) {
            plugin.getLogger().warning("Update check: GitHub returned HTTP " + status);
            return null;
        }
        return githubRelease(Json.parse(response.body()));
    }

    static Release githubRelease(Json root) {
        if (root == null || root.isNull() || root.get("draft").asBoolean(false)
                || root.get("prerelease").asBoolean(false)) {
            return null;
        }
        String tag = root.get("tag_name").text();
        if (tag == null || tag.isBlank()) return null;
        String number = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;

        return new Release(number, "GitHub", root.get("html_url").text(), List.of(), releaseJar(root));
    }

    private static PluginUpdater.Download releaseJar(Json release) {
        Json chosen = null;
        for (Json asset : release.get("assets").values()) {
            String name = asset.get("name").text();
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (chosen == null || name.toLowerCase(Locale.ROOT).startsWith("cdisc")) chosen = asset;
        }
        if (chosen == null) return null;

        String digest = chosen.get("digest").text();
        if (digest == null || !digest.toLowerCase(Locale.ROOT).startsWith("sha256:")) return null;
        return new PluginUpdater.Download(chosen.get("browser_download_url").text(),
                chosen.get("name").text(), "SHA-256", digest.substring("sha256:".length()));
    }

    private static boolean runsOnBukkit(Json entry) {
        for (Json loader : entry.get("loaders").values()) {
            String name = loader.text();
            if (name != null && BUKKIT_LOADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static PluginUpdater.Download primaryJar(Json entry) {
        Json chosen = null;
        for (Json file : entry.get("files").values()) {
            String name = file.get("filename").text();
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (chosen == null || file.get("primary").asBoolean(false)) chosen = file;
        }
        if (chosen == null) return null;
        return new PluginUpdater.Download(chosen.get("url").text(),
                chosen.get("filename").text(), "SHA-512", chosen.get("hashes").get("sha512").text());
    }

    private static List<String> textList(Json array) {
        List<String> out = new ArrayList<>();
        for (Json item : array.values()) {
            String text = item.text();
            if (text != null) out.add(text);
        }
        return out;
    }

    private static String serverMinecraftVersion() {
        String raw = Bukkit.getBukkitVersion();
        int dash = raw.indexOf('-');
        return dash > 0 ? raw.substring(0, dash) : raw;
    }

    static int compareVersions(String a, String b) {
        int[] left = numericParts(a);
        int[] right = numericParts(b);
        int len = Math.max(left.length, right.length);

        for (int i = 0; i < len; i++) {
            int x = i < left.length ? left[i] : 0;
            int y = i < right.length ? right[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int[] numericParts(String version) {
        if (version == null) return new int[0];

        List<Integer> parts = new ArrayList<>();
        Matcher matcher = DIGITS.matcher(version);
        while (matcher.find()) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException e) {

                parts.add(0);
            }
        }

        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = parts.get(i);
        return out;
    }
}
