package dev.valkdz.cdisc.command;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.lyrics.HologramStyle;
import dev.valkdz.cdisc.lyrics.PresetOffers;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.util.Chat;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class PresetSubcommand {

    public static final List<String> SUBS = List.of("share", "accept", "deny");

    private final Main plugin;

    public PresetSubcommand(Main plugin) {
        this.plugin = plugin;
    }

    public void handle(Player player, String[] args) {
        if (!plugin.cdiscConfig().isLyricsEnabled()) {
            player.sendMessage("§c" + message(player, "gui.lyrics_look.disabled"));
            return;
        }

        if (args.length < 2) {
            plugin.getLyricsGuiManager().openPreset(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "share" -> {
                if (plugin.getPermissions().require(player, Action.LYRICS_SHARE)) {
                    share(player, args);
                }
            }
            case "accept" -> accept(player);
            case "deny" -> deny(player);
            default -> player.sendMessage("§c" + message(player, "command.preset.usage"));
        }
    }

    private void share(Player from, String[] args) {
        if (args.length < 3) {
            shareUsage(from);
            return;
        }

        HologramStyle mine = plugin.getHologramPresets().get(from.getUniqueId());
        if (mine == null) {
            from.sendMessage("§c" + message(from, "command.preset.none_of_yours"));
            return;
        }

        Player to = plugin.getServer().getPlayerExact(args[2]);
        if (to == null) {
            from.sendMessage("§c" + message(from, "command.preset.no_such_player", args[2]));
            return;
        }

        if (to.equals(from)) {
            from.sendMessage("§c" + message(from, "command.preset.yourself"));
            return;
        }

        boolean replaced = plugin.getPresetOffers()
                .offer(to.getUniqueId(), from.getUniqueId(), from.getName(), mine);

        from.sendMessage("§a" + message(from, "command.preset.sent", to.getName(),
                PresetOffers.lifetimeSeconds()));
        if (replaced) {
            from.sendMessage("§7" + message(from, "command.preset.replaced", to.getName()));
        }

        ask(to, from.getName());
    }

    private void ask(Player to, String fromName) {
        to.sendMessage("");
        to.sendMessage("§e" + message(to, "command.preset.offer", fromName));

        Chat.send(to,
                Chat.command("§a[" + message(to, "command.preset.accept_button") + "]",
                        "/cdisc preset accept", message(to, "command.preset.accept_hover")),
                Chat.block("§7 "),
                Chat.command("§c[" + message(to, "command.preset.deny_button") + "]",
                        "/cdisc preset deny", message(to, "command.preset.deny_hover")));

        to.sendMessage("§8" + message(to, "command.preset.offer_note",
                PresetOffers.lifetimeSeconds()));
    }

    private void accept(Player player) {
        PresetOffers.Offer offer = plugin.getPresetOffers().claim(player.getUniqueId());
        if (offer == null) {
            player.sendMessage("§c" + message(player, "command.preset.nothing_waiting"));
            return;
        }

        plugin.getHologramPresets().set(player.getUniqueId(), offer.style());
        plugin.getLyricsDisplay().presetChanged(player);

        player.sendMessage("§a" + message(player, "command.preset.accepted", offer.fromName()));

        Player from = plugin.getServer().getPlayer(offer.from());
        if (from != null) {
            from.sendMessage("§a" + message(from, "command.preset.they_accepted", player.getName()));
        }
    }

    private void deny(Player player) {
        PresetOffers.Offer offer = plugin.getPresetOffers().claim(player.getUniqueId());
        if (offer == null) {
            player.sendMessage("§c" + message(player, "command.preset.nothing_waiting"));
            return;
        }

        player.sendMessage("§7" + message(player, "command.preset.denied", offer.fromName()));

        Player from = plugin.getServer().getPlayer(offer.from());
        if (from != null) {
            from.sendMessage("§7" + message(from, "command.preset.they_denied", player.getName()));
        }
    }

    public List<String> complete(Player player, String[] args) {
        if (args.length == 2) {
            return SUBS.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("share")) {

            return plugin.getServer().getOnlinePlayers().stream()
                    .filter(online -> !online.equals(player))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT)
                            .startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
        }
        return List.of();
    }

    private void shareUsage(Player player) {
        player.sendMessage("§c" + message(player, "command.preset.share_usage"));
    }

    private String message(Player player, String path, Object... args) {
        return plugin.getMessageManager().get(player, path, args);
    }
}
