package dev.valkdz.cdisc.command;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LocalMusicLibrary;
import dev.valkdz.cdisc.gui.dialog.Dialogs;
import dev.valkdz.cdisc.horn.GoatHorns;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.permission.Perms;
import dev.valkdz.cdisc.util.ItemUtils;
import dev.valkdz.cdisc.util.PvDiscs;
import dev.valkdz.cdisc.util.SneakMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Stream;

public class CDiscCommand implements CommandExecutor, TabCompleter {

    private static final int COMPLETION_LIMIT = 40;

    private final Main plugin;
    private final PlayerSubcommand playerSub;
    private final PairSubcommand pairSub;
    private final PresetSubcommand presetSub;

    public CDiscCommand(Main plugin) {
        this.plugin = plugin;
        this.playerSub = new PlayerSubcommand(plugin);
        this.pairSub = new PairSubcommand(plugin);
        this.presetSub = new PresetSubcommand(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (args.length == 0) return false;

        String[] parts = rewrite(args);
        String group = parts[0].toLowerCase(Locale.ROOT);
        String sub = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "";

        if (group.equals("admin")) {
            admin(sender, sub, parts);
            return true;
        }

        if (group.equals("pick")) {
            pick(sender, parts.length > 1 ? parts[1] : "");
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c" + message(sender, "cdisc.player_only", "/cdisc admin"));
            return true;
        }

        switch (group) {
            case "create" -> create(p, parts);
            case "disc" -> disc(p, sub, parts);
            case "preset" -> presetSub.handle(p, parts);
            case "player" -> playerSub.handle(p, parts);
            case "pair" -> pairSub.handle(p, parts);
            case "messages" -> messages(p, parts);
            case "sneak" -> sneak(p, parts);
            default -> p.sendMessage("§c" + message(p, "cdisc.unknown", parts[0]));
        }
        return true;
    }

    private static final Map<String, String[]> MOVED = Map.of(
            "clear", new String[]{"disc", "clear"},
            "convert", new String[]{"disc", "convert"},
            "create-playlist", new String[]{"disc", "playlist"},
            "hologram", new String[]{"preset"},
            "reload", new String[]{"admin", "reload"},
            "doctor", new String[]{"admin", "doctor"},
            "download", new String[]{"admin", "download"},
            "logs", new String[]{"admin", "logs"});

    private static String[] rewrite(String[] args) {
        String[] moved = MOVED.get(args[0].toLowerCase(Locale.ROOT));
        if (moved == null) return args;

        String[] out = new String[moved.length + args.length - 1];
        System.arraycopy(moved, 0, out, 0, moved.length);
        System.arraycopy(args, 1, out, moved.length, args.length - 1);
        return out;
    }

    private static String[] shift(String[] parts) {
        return Arrays.copyOfRange(parts, 1, parts.length);
    }

    private void create(Player p, String[] parts) {
        if (!allowed(p, Action.DISC_CREATE)) return;
        if (parts.length < 2) {
            p.sendMessage("§c" + message(p, "cdisc.create_usage"));
            return;
        }

        String query = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        ItemStack item = recordableInHand(p);
        if (item == null) {
            p.sendMessage("§c" + message(p, holdMessage()));
            return;
        }
        if (!creationAllowed(p, query)) return;

        plugin.getPermissions().markCreated(p);
        p.sendMessage("§e" + message(p, "lavaplayer.track.loading"));
        plugin.getAudioPlayerManager().createDisc(p, item, query);
    }

    private ItemStack recordableInHand(Player p) {
        ItemStack disc = ItemUtils.getDiscInHand(p);
        return disc != null ? disc : GoatHorns.inHand(plugin, p);
    }

    private String holdMessage() {
        return plugin.cdiscConfig().isGoatHornEnabled() ? "horn.hold" : "cdisc.hold_disc";
    }

    private void disc(Player p, String sub, String[] parts) {
        switch (sub) {
            case "clear" -> {
                if (!allowed(p, Action.DISC_CLEAR)) return;
                ItemStack item = recordableInHand(p);
                if (item == null) {
                    p.sendMessage("§c" + message(p, holdMessage()));
                    return;
                }
                if (GoatHorns.isHorn(item)) {
                    GoatHorns.clear(item);
                    p.sendMessage("§a" + message(p, "horn.cleared"));
                    return;
                }
                ItemUtils.clearDisc(item);
                p.sendMessage("§a" + message(p, "cdisc.cleared"));
            }
            case "convert" -> {
                if (allowed(p, Action.DISC_CREATE)) convert(p);
            }
            case "playlist" -> {
                if (!allowed(p, Action.DISC_PLAYLIST)) return;
                if (parts.length < 3) {
                    p.sendMessage("§c" + message(p, "playlist.usage"));
                    return;
                }
                String query = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
                if (!creationAllowed(p, query)) return;
                plugin.getPlaylistGuiManager().openFor(p, query);
            }
            default -> p.sendMessage("§c" + message(p, "cdisc.disc_usage"));
        }
    }

    private void messages(Player p, String[] parts) {
        if (!allowed(p, Action.PLAYER_MESSAGES)) return;

        boolean on = parts.length > 1
                ? parseToggle(parts[1], dev.valkdz.cdisc.util.PlayerPrefs.showsTrackMessages(p))
                : !dev.valkdz.cdisc.util.PlayerPrefs.showsTrackMessages(p);

        dev.valkdz.cdisc.util.PlayerPrefs.setTrackMessages(p, on);
        p.sendMessage("§a" + message(p,
                on ? "gui.track_messages.enabled" : "gui.track_messages.disabled"));
    }

    private void sneak(Player p, String[] parts) {
        if (!allowed(p, Action.PLAYER_INFO)) return;

        SneakMode current = plugin.getTrackProgressDisplay().sneakMode(p);
        SneakMode mode = parts.length > 1 ? SneakMode.byKey(parts[1]) : current.next();
        if (mode == null) {
            p.sendMessage("§c" + message(p, "cdisc.sneak_usage"));
            return;
        }

        dev.valkdz.cdisc.util.PlayerPrefs.setSneakMode(p, mode);
        plugin.getTrackProgressDisplay().stopWatching(p);
        p.sendMessage("§a" + message(p, switch (mode) {
            case TOGGLE -> "cdisc.sneak_toggle";
            case RELEASE -> "cdisc.sneak_release";
            case OFF -> "cdisc.sneak_off";
        }));
    }

    private void admin(CommandSender sender, String sub, String[] parts) {
        switch (sub) {
            case "doctor" -> {
                if (!allowed(sender, Action.ADMIN_DOCTOR)) return;
                for (String line : Diagnostics.report(plugin)) {
                    sender.sendMessage(line.replace('&', '§'));
                }
            }
            case "download" -> {
                if (allowed(sender, Action.DISC_DOWNLOAD)) download(sender, shift(parts));
            }
            case "reload" -> {
                if (allowed(sender, Action.ADMIN_RELOAD)) reload(sender);
            }
            case "logs" -> {
                if (allowed(sender, Action.ADMIN_LOGS)) logs(sender);
            }
            case "config" -> config(sender);
            default -> {
                if (Perms.isAdmin(sender)) {
                    sender.sendMessage("§c" + message(sender, "cdisc.admin_usage"));
                }
            }
        }
    }

    private void logs(CommandSender sender) {
        List<String> doctor = Diagnostics.report(plugin);
        sender.sendMessage("§7" + message(sender, "cdisc.logs_uploading"));

        dev.valkdz.cdisc.util.Tasks.async(plugin, () -> {
            List<String> reply;
            try {
                reply = List.of(
                        "§a" + message(sender, "cdisc.logs_uploaded", LogUpload.upload(plugin, doctor)),
                        message(sender, "cdisc.logs_issue").replace('&', '§'));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                reply = List.of("§c" + message(sender, "cdisc.logs_failed", String.valueOf(e.getMessage())));
            }
            List<String> lines = reply;
            dev.valkdz.cdisc.util.Tasks.global(plugin, () -> lines.forEach(sender::sendMessage));
        });
    }

    private void config(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c" + message(sender, "cdisc.player_only", "/cdisc admin"));
            return;
        }
        if (!p.hasPermission(Perms.CONFIG)) {
            p.sendMessage("§c" + message(p, "perms.denied", Perms.CONFIG));
            return;
        }
        if (!plugin.cdiscConfig().isConfigDialogEnabled()) {
            p.sendMessage("§c" + message(p, "config_dialog.disabled"));
            return;
        }
        if (!Dialogs.supported()) {
            p.sendMessage("§c" + message(p, "config_dialog.unsupported"));
            return;
        }
        Dialogs.openConfig(plugin, p);
    }

    private void reload(CommandSender sender) {
        plugin.reloadEverything();
        sender.sendMessage("§a" + message(sender, "cdisc.reloaded"));
    }

    private void hologramApply(Player p, String[] args) {
        if (!args[1].equalsIgnoreCase("apply") || args.length < 3) {
            p.sendMessage("§c" + message(p, "command.hologram.usage"));
            return;
        }

        Player from = plugin.getServer().getPlayerExact(args[2]);
        if (from == null) {
            p.sendMessage("§c" + message(p, "command.hologram.no_such_player", args[2]));
            return;
        }

        if (!plugin.getHologramPresets().copy(from.getUniqueId(), p.getUniqueId())) {
            p.sendMessage("§c" + message(p, "command.hologram.none_of_theirs", from.getName()));
            return;
        }

        plugin.presetChanged(p);
        p.sendMessage("§a" + message(p, "command.hologram.copied", from.getName()));
    }

    private void pick(CommandSender sender, String raw) {
        int number;
        try {
            number = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return;
        }

        dev.valkdz.cdisc.audio.SearchResults.Pick picked =
                plugin.getSearchResults().claim(sender, number);
        if (picked == null) {

            sender.sendMessage("§c" + message(sender, "search.expired"));
            return;
        }

        if (picked.kind() == dev.valkdz.cdisc.audio.SearchResults.Kind.DOWNLOAD) {
            if (!allowed(sender, Action.DISC_DOWNLOAD)) return;
            plugin.getTrackDownloader().pick(sender, picked.track(), picked.address(), null);
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c" + message(sender, "cdisc.player_only", "/cdisc admin"));
            return;
        }
        if (!allowed(player, Action.DISC_CREATE)) return;

        ItemStack item = recordableInHand(player);
        if (item == null) {
            player.sendMessage("§c" + message(player, holdMessage()));
            return;
        }

        plugin.getPermissions().markCreated(player);
        player.sendMessage("§e" + plugin.getMessageManager().get(player, "lavaplayer.track.loading"));
        plugin.getAudioPlayerManager()
                .writePickedTrack(player, item, picked.track(), picked.address());
    }

    private void convert(Player player) {
        ItemStack item = ItemUtils.getDiscInHand(player);

        if (item == null) {

            boolean burnedNonDisc =
                    PvDiscs.isPvDisc(player.getInventory().getItemInMainHand())
                            || PvDiscs.isPvDisc(player.getInventory().getItemInOffHand());
            String message = burnedNonDisc ? "convert.disc_only" : "cdisc.hold_disc";
            player.sendMessage("§c" + plugin.getMessageManager().get(player, message));
            return;
        }

        if (ItemUtils.isCdiscDisc(item)) {
            player.sendMessage("§e" + plugin.getMessageManager().get(player, "convert.already"));
            return;
        }

        String identifier = PvDiscs.identifierOf(item);
        if (identifier == null) {
            player.sendMessage("§c" + plugin.getMessageManager().get(player, "convert.not_found"));
            return;
        }

        if (!plugin.getPermissions().isHostAllowed(player, identifier)) {
            String host = dev.valkdz.cdisc.permission.PermissionsConfig.hostOf(identifier);
            player.sendMessage("§c" + plugin.getMessageManager()
                    .get(player, "perms.host_blocked", host == null ? "?" : host));
            return;
        }

        String title = PvDiscs.nameOf(item);
        if (title == null) title = plugin.getMessageManager().get(player, "convert.unknown_track");
        String author = plugin.getMessageManager().get(player, "convert.unknown_author");

        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {

            PvDiscs.strip(meta);
            meta.setDisplayName(null);
            item.setItemMeta(meta);
        }

        ItemUtils.saveTrackToDisc(item, identifier, title, author);
        player.sendMessage("§a" + plugin.getMessageManager().get(player, "convert.done", title));
    }

    private String message(CommandSender sender, String path, Object... args) {
        Player player = sender instanceof Player p ? p : null;
        return plugin.getMessageManager().get(player, path, args);
    }

    private void download(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c" + message(sender, "download.usage"));
            return;
        }

        String first = args[1];
        String rest = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : null;

        if (!isLink(first)) {

            plugin.getTrackDownloader().download(sender,
                    String.join(" ", Arrays.copyOfRange(args, 1, args.length)), null);
            return;
        }

        if (resolvable(first)) {
            plugin.getTrackDownloader().download(sender, first, rest);
            return;
        }

        sender.sendMessage("§e" + message(sender, "download.started"));
        plugin.getLocalDownloader().download(first, rest, result -> {
            if (result.ok()) {
                sender.sendMessage("§a" + message(sender, "download.done", result.detail()));
                sender.sendMessage("§7" + message(sender, "download.hint",
                        LocalMusicLibrary.PREFIX + result.detail()));
                return;
            }
            sender.sendMessage("§c" + message(sender,
                    dev.valkdz.cdisc.audio.TrackDownloader.failureKey(result.status()),
                    result.detail() == null ? "?" : result.detail()));
        });
    }

    private static boolean isLink(String word) {
        return word.regionMatches(true, 0, "http://", 0, 7)
                || word.regionMatches(true, 0, "https://", 0, 8);
    }

    private boolean resolvable(String url) {
        return plugin.getAudioPlayerManager().getTrackLoader().isYoutubeIdentifier(url)
                || url.toLowerCase(Locale.ROOT).contains("open.spotify.com");
    }

    private boolean creationAllowed(Player player, String query) {
        long wait = plugin.getPermissions().cooldownRemaining(player);
        if (wait > 0) {
            player.sendMessage("§c" + plugin.getMessageManager()
                    .get(player, "perms.cooldown", String.valueOf(wait)));
            return false;
        }

        if (!plugin.getPermissions().isHostAllowed(player, query)) {
            String host = dev.valkdz.cdisc.permission.PermissionsConfig.hostOf(query);
            player.sendMessage("§c" + plugin.getMessageManager()
                    .get(player, "perms.host_blocked", host == null ? "?" : host));
            return false;
        }
        return true;
    }

    private String adminEntry(String name, CommandSender sender) {
        return Perms.isAdmin(sender) ? name : null;
    }

    private String entry(String name, Action action, CommandSender sender) {
        return plugin.getPermissions().allows(sender, action) ? name : null;
    }

    private String playerEntry(String name, Action action, CommandSender sender) {
        return sender instanceof Player ? entry(name, action, sender) : null;
    }

    private boolean allowed(CommandSender sender, Action action) {
        return plugin.getPermissions().require(sender, action);
    }

    private static boolean parseToggle(String raw, boolean current) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "enabled" -> true;
            case "off", "false", "disable", "disabled" -> false;
            default -> !current;
        };
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, String[] args) {
        if (args.length == 1) {

            Stream<String> subs = Stream.of(
                    playerEntry("create", Action.DISC_CREATE, sender),
                    playerEntry("disc", Action.DISC_CREATE, sender),
                    playerEntry("preset", Action.LYRICS_PRESET, sender),
                    playerEntry("player", Action.PLAYER_GUI, sender),
                    playerEntry("pair", Action.PAIR_LIST, sender),
                    playerEntry("messages", Action.PLAYER_MESSAGES, sender),
                    playerEntry("sneak", Action.PLAYER_INFO, sender),
                    adminEntry("admin", sender)
            ).filter(java.util.Objects::nonNull);

            return subs.filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
        }

        if (args[0].equalsIgnoreCase("disc") && args.length == 2) {
            return Stream.of("clear", "convert", "playlist")
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args[0].equalsIgnoreCase("admin") && args.length == 2) {
            return Stream.of(
                            entry("reload", Action.ADMIN_RELOAD, sender),
                            entry("doctor", Action.ADMIN_DOCTOR, sender),
                            entry("logs", Action.ADMIN_LOGS, sender),
                            entry("download", Action.DISC_DOWNLOAD, sender),
                            sender instanceof Player && sender.hasPermission(Perms.CONFIG)
                                    && plugin.cdiscConfig().isConfigDialogEnabled() ? "config" : null)
                    .filter(java.util.Objects::nonNull)
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
        }
        if (args[0].equalsIgnoreCase("preset") && sender instanceof Player player
                && plugin.getPermissions().allows(sender, Action.LYRICS_PRESET)) {
            return presetSub.complete(player, args);
        }
        if (args[0].equalsIgnoreCase("player")) {
            return playerSub.complete(args);
        }
        if (args[0].equalsIgnoreCase("messages") && args.length == 2) {
            return Stream.of("on", "off")
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args[0].equalsIgnoreCase("sneak") && args.length == 2) {
            return Stream.of("toggle", "release", "off")
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args[0].equalsIgnoreCase("pair")) {
            return pairSub.complete(args);
        }
        if (args[0].equalsIgnoreCase("create") && sender instanceof Player
                && plugin.getPermissions().allows(sender, Action.DISC_CREATE)) {
            return completeLocalFile(args);
        }
        return Collections.emptyList();
    }

    private List<String> completeLocalFile(String[] args) {
        LocalMusicLibrary library = plugin.getLocalMusic();
        if (library == null || !library.isEnabled()) return Collections.emptyList();

        String typed = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (!LocalMusicLibrary.isLocalQuery(typed)) {

            boolean startingPrefix = args.length == 2 && !typed.isEmpty()
                    && LocalMusicLibrary.PREFIX.startsWith(typed.toLowerCase(Locale.ROOT));
            return startingPrefix ? List.of(LocalMusicLibrary.PREFIX) : Collections.emptyList();
        }

        int lastWord = typed.lastIndexOf(' ') + 1;
        return library.complete(typed, COMPLETION_LIMIT).stream()
                .map(suggestion -> suggestion.substring(lastWord))
                .toList();
    }
}
