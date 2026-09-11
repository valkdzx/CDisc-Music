package dev.valkdz.cdisc.permission;

import dev.valkdz.cdisc.Main;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PermissionsConfig {

    public static final String FILE_NAME = "permissions.yml";

    private static final Map<String, List<Action>> LEGACY_NODES = legacyNodes();

    private final Main plugin;
    private final File file;
    private FileConfiguration cfg;

    private final Map<Action, PermissionRule> rules = new EnumMap<>(Action.class);

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
        readRules();
        register();
    }

    private void readRules() {
        rules.clear();
        for (Action action : Action.values()) {
            PermissionRule rule = PermissionRule.parse(cfg.get("actions." + action.path()));
            rules.put(action, rule.isEmpty() ? PermissionRule.parse(action.fallback()) : rule);
        }
    }

    private void register() {
        for (Action action : Action.values()) {
            PermissionDefault fromRule = rules.get(action).asDefault();
            replace(new Permission(action.node(),
                    fromRule == null ? PermissionDefault.FALSE : fromRule));
        }
        replace(new Permission(Perms.ADMIN, PermissionDefault.OP));

        for (Map.Entry<String, List<Action>> legacy : LEGACY_NODES.entrySet()) {
            Map<String, Boolean> children = new HashMap<>();
            for (Action action : legacy.getValue()) {
                children.put(action.node(), true);
            }
            replace(new Permission(legacy.getKey(), PermissionDefault.FALSE, children));
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.recalculatePermissions();
        }
    }

    private void replace(Permission permission) {
        Permission existing = Bukkit.getPluginManager().getPermission(permission.getName());
        if (existing != null) {
            Bukkit.getPluginManager().removePermission(existing);
        }
        Bukkit.getPluginManager().addPermission(permission);
    }

    public boolean allows(CommandSender sender, Action action) {
        if (Perms.isAdmin(sender)) return true;

        PermissionRule rule = rules.get(action);
        if (rule == null) return sender.hasPermission(action.node());

        // A plain true/op/false is the default of the node itself, so a permission plugin can
        // still overrule it per player. A named right is a second way in, never a way out.
        if (rule.asDefault() != null) return sender.hasPermission(action.node());
        return rule.test(sender) || sender.hasPermission(action.node());
    }

    public boolean require(CommandSender sender, Action action) {
        if (allows(sender, action)) return true;

        sender.sendMessage("§c" + plugin.getMessageManager().get(
                sender instanceof Player player ? player : null, "perms.denied", action.node()));
        return false;
    }

    public PermissionRule ruleOf(Action action) {
        return rules.get(action);
    }

    static Map<String, List<Action>> legacyNodes() {
        Map<String, List<Action>> legacy = new LinkedHashMap<>();

        legacy.put("cdisc.create", List.of(Action.DISC_CREATE, Action.DISC_CONVERT));
        legacy.put("cdisc.create.playlist", List.of(Action.DISC_PLAYLIST));
        legacy.put("cdisc.clear", List.of(Action.DISC_CLEAR));
        legacy.put("cdisc.download", List.of(Action.DISC_DOWNLOAD));
        legacy.put("cdisc.doctor", List.of(Action.ADMIN_DOCTOR));
        legacy.put("cdisc.portable", List.of(Action.PLAYER_PORTABLE));
        legacy.put("cdisc.messages", List.of(Action.PLAYER_MESSAGES));
        legacy.put("cdisc.preset", List.of(Action.LYRICS_PRESET, Action.LYRICS_SHARE));

        legacy.put("cdisc.player", List.of(
                Action.PLAYER_GUI, Action.PLAYER_PLAY, Action.PLAYER_PAUSE, Action.PLAYER_NEXT,
                Action.PLAYER_PREVIOUS, Action.PLAYER_SEEK, Action.PLAYER_REPEAT,
                Action.PLAYER_SHUFFLE, Action.PLAYER_VOLUME, Action.PLAYER_LOCAL_VOLUME,
                Action.PLAYER_BEACON, Action.PLAYER_CHANNELS, Action.PLAYER_SCREEN,
                Action.PLAYER_INFO, Action.QUEUE_OPEN, Action.QUEUE_ADD, Action.QUEUE_REMOVE,
                Action.QUEUE_PLAY, Action.QUEUE_POLICY, Action.LYRICS_TOGGLE,
                Action.LYRICS_PRESET, Action.LYRICS_SCOREBOARD));

        legacy.put("cdisc.pair", List.of(Action.PAIR_CREATE, Action.PAIR_MANAGE,
                Action.PAIR_SETTINGS, Action.PAIR_DISSOLVE, Action.PAIR_LIST));

        return Map.copyOf(legacy);
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
