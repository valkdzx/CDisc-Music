package dev.valkdz.cdisc.permission;

import dev.valkdz.cdisc.Main;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PermissionsConfig {

    public static final String FILE_NAME = "permissions.yml";

    private final Main plugin;
    private final File file;
    private FileConfiguration cfg;

    private final Map<UUID, Long> lastCreate = new ConcurrentHashMap<>();

    public PermissionsConfig(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);

        InputStream bundled = plugin.getResource(FILE_NAME);
        if (bundled != null) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(bundled, StandardCharsets.UTF_8)));
        }
        register();
    }

    private void register() {
        for (String node : Perms.ALL) {
            Permission existing = Bukkit.getPluginManager().getPermission(node);
            if (existing != null) {
                Bukkit.getPluginManager().removePermission(existing);
            }
            Bukkit.getPluginManager().addPermission(
                    new Permission(node, defaultOf(node)));
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.recalculatePermissions();
        }
    }

    private PermissionDefault defaultOf(String node) {
        String raw = cfg.getString("defaults." + node, node.equals(Perms.ADMIN) ? "op" : "true");
        return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
            case "op", "operator" -> PermissionDefault.OP;
            case "false", "none", "no" -> PermissionDefault.FALSE;
            case "notop", "not_op" -> PermissionDefault.NOT_OP;
            default -> PermissionDefault.TRUE;
        };
    }

    public int createCooldownSeconds() {
        return Math.max(0, cfg.getInt("limits.create-cooldown-seconds", 5));
    }

    public int maxTrackSeconds() {
        return Math.max(0, cfg.getInt("limits.max-track-seconds", 0));
    }

    public boolean allowLive() {
        return cfg.getBoolean("limits.allow-live", true);
    }

    public String trackRejection(Player player, boolean live, long lengthMs) {
        if (!Perms.isLimited(player)) return null;
        if (live) return allowLive() ? null : "perms.live_blocked";

        int max = maxTrackSeconds();
        if (max > 0 && lengthMs > 0 && lengthMs / 1000L > max) {
            return "perms.track_too_long";
        }
        return null;
    }

    public long cooldownRemaining(Player player) {
        if (!Perms.isLimited(player)) return 0;

        int cooldown = createCooldownSeconds();
        if (cooldown <= 0) return 0;

        Long last = lastCreate.get(player.getUniqueId());
        if (last == null) return 0;

        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        return Math.max(0, cooldown - elapsed);
    }

    public void markCreated(Player player) {
        lastCreate.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public boolean isHostAllowed(Player player, String query) {
        if (!Perms.isLimited(player)) return true;

        String host = hostOf(query);
        if (host == null) return true;

        for (String blocked : cfg.getStringList("limits.blocked-hosts")) {
            if (matches(host, blocked)) return false;
        }

        List<String> allowed = cfg.getStringList("limits.allowed-hosts");
        if (allowed.isEmpty()) return true;

        for (String entry : allowed) {
            if (matches(host, entry)) return true;
        }
        return false;
    }

    public static String hostOf(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (!trimmed.regionMatches(true, 0, "http://", 0, 7)
                && !trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            return null;
        }

        try {
            String host = URI.create(trimmed).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {

            return null;
        }
    }

    private static boolean matches(String host, String rule) {
        String clean = rule == null ? "" : rule.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) return false;
        if (clean.startsWith("www.")) clean = clean.substring(4);
        return host.equals(clean) || host.endsWith("." + clean);
    }
}
