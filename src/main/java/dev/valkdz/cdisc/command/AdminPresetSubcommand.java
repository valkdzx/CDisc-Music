package dev.valkdz.cdisc.command;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.feature.lyrics.HologramPresets;
import dev.valkdz.cdisc.feature.lyrics.HologramStyle;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class AdminPresetSubcommand {

    public static final List<String> SUBS = List.of("add", "set", "unset", "default", "remove");

    private static final List<String> SELECTORS = List.of("@a", "@p", "@r", "@s");

    private final Main plugin;

    public AdminPresetSubcommand(Main plugin) {
        this.plugin = plugin;
    }

    public void handle(CommandSender sender, String[] parts) {
        if (!plugin.cdiscConfig().isLyricsEnabled()) {
            sender.sendMessage("§c" + message(sender, "gui.lyrics_look.disabled"));
            return;
        }

        String sub = parts.length > 2 ? parts[2].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "add" -> add(sender, parts);
            case "set" -> set(sender, parts);
            case "unset" -> unset(sender, parts);
            case "default" -> fallback(sender, parts);
            case "remove" -> remove(sender, parts);
            default -> usage(sender);
        }
    }

    private void add(CommandSender sender, String[] parts) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c" + message(sender, "cdisc.player_only", "/cdisc admin presets set"));
            return;
        }
        if (parts.length < 4) {
            usage(sender);
            return;
        }

        String name = HologramPresets.normalise(parts[3]);
        if (!HologramPresets.validName(name)) {
            sender.sendMessage("§c" + message(sender, "command.server_preset.bad_name"));
            return;
        }

        HologramPresets presets = plugin.getHologramPresets();
        if (presets.server(name) == null) {
            presets.setServer(name, HologramStyle.fromConfig(plugin.cdiscConfig()));
            player.sendMessage("§a" + message(player, "command.server_preset.created", name));
        } else {
            player.sendMessage("§e" + message(player, "command.server_preset.editing", name));
        }
        plugin.getLyricsGuiManager().openServerPreset(player, name);
    }

    private void set(CommandSender sender, String[] parts) {
        if (parts.length < 5) {
            usage(sender);
            return;
        }

        String name = existing(sender, parts[3]);
        if (name == null) return;

        Boolean forced = forced(sender, parts, 5);
        if (forced == null) return;

        List<Player> targets = select(sender, parts[4]);
        if (targets == null || targets.isEmpty()) return;

        for (Player target : targets) {
            plugin.getHologramPresets().assign(target.getUniqueId(), name, forced);
            changed(target);
        }
        sender.sendMessage("§a" + message(sender, forced
                ? "command.server_preset.set_forced" : "command.server_preset.set", targets.size(), name));
    }

    private void fallback(CommandSender sender, String[] parts) {
        HologramPresets presets = plugin.getHologramPresets();
        if (parts.length < 4) {
            HologramPresets.Assignment everyone = presets.fallback();
            if (everyone == null) {
                sender.sendMessage("§7" + message(sender, "command.server_preset.default_nothing"));
            } else {
                sender.sendMessage("§7" + message(sender, everyone.forced()
                        ? "command.server_preset.default_current_forced"
                        : "command.server_preset.default_current", everyone.preset()));
            }
            return;
        }

        if (parts[3].equalsIgnoreCase("none")) {
            presets.setFallback(null);
            sender.sendMessage("§a" + message(sender, "command.server_preset.default_cleared"));
            refreshEveryone();
            return;
        }

        String name = existing(sender, parts[3]);
        if (name == null) return;
        Boolean forced = forced(sender, parts, 4);
        if (forced == null) return;

        presets.setFallback(new HologramPresets.Assignment(name, forced));
        sender.sendMessage("§a" + message(sender, forced
                ? "command.server_preset.default_set_forced" : "command.server_preset.default_set", name));
        refreshEveryone();
    }

    private Boolean forced(CommandSender sender, String[] parts, int index) {
        if (parts.length <= index) return false;

        String raw = parts[index].toLowerCase(Locale.ROOT);
        if (raw.equals("true") || raw.equals("false")) return raw.equals("true");
        usage(sender);
        return null;
    }

    private void unset(CommandSender sender, String[] parts) {
        if (parts.length < 4) {
            usage(sender);
            return;
        }

        List<Player> targets = select(sender, parts[3]);
        if (targets == null || targets.isEmpty()) return;

        int freed = 0;
        for (Player target : targets) {
            if (!plugin.getHologramPresets().unassign(target.getUniqueId())) continue;
            freed++;
            changed(target);
        }
        sender.sendMessage("§a" + message(sender, "command.server_preset.unset", freed));
    }

    private void remove(CommandSender sender, String[] parts) {
        if (parts.length < 4) {
            usage(sender);
            return;
        }

        String name = existing(sender, parts[3]);
        if (name == null) return;

        int freed = plugin.getHologramPresets().removeServer(name).size();
        sender.sendMessage("§a" + message(sender, "command.server_preset.removed", name, freed));
        refreshEveryone();
    }

    private String existing(CommandSender sender, String raw) {
        String name = HologramPresets.normalise(raw);
        if (plugin.getHologramPresets().server(name) != null) return name;

        sender.sendMessage("§c" + message(sender, "command.server_preset.no_such", raw));
        return null;
    }

    private List<Player> select(CommandSender sender, String raw) {
        List<Player> found = resolve(sender, raw);
        if (found == null) {
            sender.sendMessage("§c" + message(sender, "command.server_preset.bad_selector", raw));
        } else if (found.isEmpty()) {
            sender.sendMessage("§c" + message(sender, "command.server_preset.no_players", raw));
        }
        return found;
    }

    private List<Player> resolve(CommandSender sender, String raw) {
        if (!raw.startsWith("@")) {
            Player exact = plugin.getServer().getPlayerExact(raw);
            return exact == null ? List.of() : List.of(exact);
        }

        try {
            return Bukkit.selectEntities(sender, raw).stream()
                    .filter(Player.class::isInstance)
                    .map(Player.class::cast)
                    .toList();
        } catch (IllegalArgumentException e) {
            return null;
        } catch (RuntimeException e) {
            // Folia has no main thread and vanilla selectors may throw there; the common
            // ones are resolved by hand.
            return switch (raw) {
                case "@a" -> new ArrayList<>(plugin.getServer().getOnlinePlayers());
                case "@s", "@p" -> sender instanceof Player self ? List.of(self) : null;
                default -> null;
            };
        }
    }

    private void changed(Player target) {
        Tasks.onEntity(plugin, target, () -> plugin.presetChanged(target));
    }

    private void refreshEveryone() {
        plugin.getServer().getOnlinePlayers().forEach(this::changed);
    }

    public List<String> complete(String[] args) {
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        String sub = args.length > 3 ? args[2].toLowerCase(Locale.ROOT) : "";

        Stream<String> options = switch (args.length) {
            case 3 -> SUBS.stream();
            case 4 -> switch (sub) {
                case "set", "remove", "add" -> plugin.getHologramPresets().serverNames().stream();
                case "default" -> Stream.concat(plugin.getHologramPresets().serverNames().stream(),
                        Stream.of("none"));
                case "unset" -> players();
                default -> Stream.empty();
            };
            case 5 -> switch (sub) {
                case "set" -> players();
                case "default" -> args[3].equalsIgnoreCase("none") ? Stream.empty() : Stream.of("true", "false");
                default -> Stream.empty();
            };
            case 6 -> sub.equals("set") ? Stream.of("true", "false") : Stream.empty();
            default -> Stream.empty();
        };
        return options.filter(s -> s.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    private Stream<String> players() {
        return Stream.concat(SELECTORS.stream(),
                plugin.getServer().getOnlinePlayers().stream().map(Player::getName).sorted());
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("§c" + message(sender, "command.server_preset.usage"));
    }

    private String message(CommandSender sender, String path, Object... args) {
        return plugin.getMessageManager().get(sender instanceof Player p ? p : null, path, args);
    }
}
