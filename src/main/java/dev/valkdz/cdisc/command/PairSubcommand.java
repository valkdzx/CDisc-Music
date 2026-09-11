package dev.valkdz.cdisc.command;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.speaker.SpeakerGroup;
import dev.valkdz.cdisc.speaker.SpeakerGroupManager;
import dev.valkdz.cdisc.speaker.SpeakerSettings;
import dev.valkdz.cdisc.util.Chat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PairSubcommand {

    public static final List<String> SUBS =
            List.of("gui", "create", "add", "tag", "channel", "volume",
                    "remove", "dissolve", "info", "list", "unlink");

    private static final int TARGET_RANGE = 6;

    private final Main plugin;

    public PairSubcommand(Main plugin) {
        this.plugin = plugin;
    }

    public void handle(Player player, String[] args) {
        String sub = args.length < 2 ? "info" : args[1].toLowerCase(Locale.ROOT);

        if (!allowed(player, sub)) return;

        switch (sub) {
            case "gui" -> gui(player);
            case "create" -> create(player, args);
            case "add" -> add(player, args);
            case "remove" -> remove(player);
            case "dissolve" -> dissolve(player);
            case "list" -> list(player);
            case "info" -> info(player);
            case "unlink" -> unlink(player);
            case "tag" -> tag(player, args);
            case "channel" -> channel(player, args);
            case "volume" -> volume(player, args);
            default -> msg(player, "§c", "command.pair.usage");
        }
    }

    private boolean allowed(Player player, String sub) {
        Action action = switch (sub) {
            case "create" -> Action.PAIR_CREATE;
            case "dissolve" -> Action.PAIR_DISSOLVE;
            case "gui", "add", "remove", "unlink" -> Action.PAIR_MANAGE;
            case "tag", "channel", "volume" -> Action.PAIR_SETTINGS;
            default -> Action.PAIR_LIST;
        };
        return plugin.getPermissions().require(player, action);
    }

    private void gui(Player player) {
        if (!plugin.cdiscConfig().isSpeakerGroupEnabled()) {
            msg(player, "§c", "command.pair.disabled");
            return;
        }

        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        Block block = targetJukebox(player);
        if (block != null && groups.groupAt(block) != null) {
            plugin.getPairGuiManager().openManage(player, block);
            return;
        }

        List<SpeakerGroup> owned = groups.ownedBy(player.getUniqueId());
        if (owned.size() == 1) {
            plugin.getPairGuiManager().openManage(player, owned.get(0).main().getBlock());
            return;
        }
        if (owned.isEmpty()) {
            msg(player, "§c", "command.pair.not_paired");
        } else {

            list(player);
        }
    }

    private void create(Player player, String[] args) {
        if (args.length < 3) {
            msg(player, "§c", "command.pair.create_usage");
            return;
        }
        Block block = targetJukebox(player);
        if (block == null) {
            msg(player, "§c", "command.pair.no_jukebox");
            return;
        }

        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        if (!plugin.cdiscConfig().isSpeakerGroupEnabled()) {
            msg(player, "§c", "command.pair.disabled");
            return;
        }
        if (groups.groupAt(block) != null) {
            msg(player, "§c", "command.pair.already_paired");
            return;
        }

        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
        if (name.isEmpty()) {
            msg(player, "§c", "command.pair.create_usage");
            return;
        }
        if (groups.byName(name) != null) {
            msg(player, "§c", "command.pair.name_taken", name);
            return;
        }

        groups.create(name, player.getUniqueId(), block);
        ok(player, "command.pair.created", name);
    }

    private void add(Player player, String[] args) {
        if (args.length < 3) {
            msg(player, "§c", "command.pair.add_usage");
            return;
        }
        Block block = targetJukebox(player);
        if (block == null) {
            msg(player, "§c", "command.pair.no_jukebox");
            return;
        }

        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
        SpeakerGroup group = groups.byName(name);
        if (group == null) {
            msg(player, "§c", "command.pair.no_such_group", name);
            return;
        }
        if (!groups.canManage(player, group)) {
            msg(player, "§c", "speaker.protected", group.name());
            return;
        }
        SpeakerGroupManager.PairResult check = groups.canAdd(group, block);
        if (check != SpeakerGroupManager.PairResult.OK) {
            reject(player, check, block);
            return;
        }
        if (!groups.addSpeaker(group, block)) {
            msg(player, "§c", "command.pair.add_failed");
            return;
        }

        plugin.getAudioPlayerManager().attachSpeakerLive(group.main().getBlock(), block);
        ok(player, "command.pair.added", group.name(), String.valueOf(group.size()));
    }

    private void reject(Player player, SpeakerGroupManager.PairResult result, Block block) {
        switch (result) {
            case DISABLED -> msg(player, "§c", "command.pair.disabled");

            case ALREADY_PAIRED -> {
                SpeakerGroup taken = plugin.getSpeakerGroupManager().groupAt(block);
                if (taken == null) {
                    msg(player, "§c", "command.pair.already_paired");
                } else {
                    msg(player, "§c", "command.pair.already_paired_by", taken.name());
                }
            }
            case DIFFERENT_WORLD -> msg(player, "§c", "command.pair.different_world");
            case TOO_FAR -> msg(player, "§c", "command.pair.too_far",
                    String.valueOf(plugin.cdiscConfig().getSpeakerMaxDistance()));
            case GROUP_FULL -> msg(player, "§c", "command.pair.group_full",
                    String.valueOf(plugin.cdiscConfig().getSpeakerMaxPerGroup()));
            default -> msg(player, "§c", "command.pair.add_failed");
        }
    }

    private void remove(Player player) {
        Block block = targetJukebox(player);
        if (block == null) {
            msg(player, "§c", "command.pair.no_jukebox");
            return;
        }

        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        SpeakerGroup group = groups.groupAt(block);
        if (group == null) {
            msg(player, "§c", "command.pair.not_paired");
            return;
        }
        if (!groups.canManage(player, group)) {
            msg(player, "§c", "speaker.protected", group.name());
            return;
        }
        if (group.isMain(block.getLocation())) {
            msg(player, "§c", "command.pair.remove_main");
            return;
        }

        plugin.getAudioPlayerManager().detachSpeakerLive(group.main().getBlock(), block);
        groups.removeSpeaker(group, block);
        ok(player, "command.pair.removed", group.name());
    }

    private void dissolve(Player player) {
        Block block = targetJukebox(player);
        if (block == null) {
            msg(player, "§c", "command.pair.no_jukebox");
            return;
        }

        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        SpeakerGroup group = groups.groupAt(block);
        if (group == null) {
            msg(player, "§c", "command.pair.not_paired");
            return;
        }

        if (!groups.canManage(player, group)) {
            msg(player, "§c", "speaker.protected", group.name());
            return;
        }

        Block main = group.main().getBlock();
        for (Location speaker : group.speakers()) {
            plugin.getAudioPlayerManager().detachSpeakerLive(main, speaker.getBlock());
        }
        String name = group.name();
        groups.dissolve(group);
        ok(player, "command.pair.dissolved", name);
    }

    private void unlink(Player player) {
        if (!player.isOp()) {
            msg(player, "§c", "command.pair.op_only");
            return;
        }
        Block block = targetJukebox(player);
        if (block == null) {
            msg(player, "§c", "command.pair.no_jukebox");
            return;
        }

        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        SpeakerGroup group = groups.groupAt(block);
        if (group == null) {
            msg(player, "§c", "command.pair.not_paired");
            return;
        }

        Block main = group.main().getBlock();
        boolean wasMain = group.isMain(block.getLocation());
        String name = group.name();

        if (wasMain) {
            for (Location speaker : group.speakers()) {
                plugin.getAudioPlayerManager().detachSpeakerLive(main, speaker.getBlock());
            }
        } else {
            plugin.getAudioPlayerManager().detachSpeakerLive(main, block);
        }
        groups.onMemberRemoved(block);

        ok(player, wasMain ? "command.pair.dissolved" : "command.pair.removed", name);
    }

    private void tag(Player player, String[] args) {
        Block block = targetJukebox(player);
        if (block == null) {
            msg(player, "§c", "command.pair.no_jukebox");
            return;
        }

        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(block);
        if (group != null && !plugin.getSpeakerGroupManager().canManage(player, group)) {
            msg(player, "§c", "speaker.protected", group.name());
            return;
        }

        String name = args.length < 3
                ? ""
                : org.bukkit.ChatColor.stripColor(
                        String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim());

        if (name.length() > SpeakerSettings.MAX_TAG_LENGTH) {
            msg(player, "§c", "command.pair.tag_too_long",
                    String.valueOf(SpeakerSettings.MAX_TAG_LENGTH));
            return;
        }

        SpeakerSettings settings = SpeakerSettings.of(block);
        SpeakerSettings.store(block, settings.withTag(name.isEmpty() ? null : name));

        if (name.isEmpty()) {
            ok(player, "command.pair.tag_cleared");
        } else {
            ok(player, "command.pair.tag_set", name);
        }
    }

    private void channel(Player player, String[] args) {
        Block block = editableJukebox(player);
        if (block == null) return;

        if (args.length < 3) {
            msg(player, "§c", "command.pair.channel_usage");
            return;
        }
        SpeakerSettings.Channel channel = SpeakerSettings.Channel.parse(args[2], null);
        if (channel == null) {
            msg(player, "§c", "command.pair.channel_usage");
            return;
        }

        SpeakerSettings settings = SpeakerSettings.of(block);
        SpeakerSettings.store(block, settings.withChannel(channel));
        reapply(block);

        ok(player, "command.pair.channel_set",
                plugin.getMessageManager().get(player, "gui.channels.mode." + channel.key()));
    }

    private void volume(Player player, String[] args) {
        Block block = editableJukebox(player);
        if (block == null) return;

        if (args.length < 3) {
            msg(player, "§c", "command.pair.volume_usage");
            return;
        }
        int value;
        try {
            value = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            msg(player, "§c", "command.pair.volume_usage");
            return;
        }

        SpeakerSettings settings = SpeakerSettings.of(block);
        SpeakerSettings settingsWithVolume = settings.withVolume(value);
        SpeakerSettings.store(block, settingsWithVolume);
        reapply(block);

        if (settingsWithVolume.isMuted()) {
            ok(player, "gui.speaker.muted");
        } else {
            ok(player, "command.pair.volume_set", String.valueOf(settingsWithVolume.volume()));
        }
    }

    private Block editableJukebox(Player player) {
        Block block = targetJukebox(player);
        if (block == null) {
            msg(player, "§c", "command.pair.no_jukebox");
            return null;
        }
        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(block);
        if (group != null && !plugin.getSpeakerGroupManager().canManage(player, group)) {
            msg(player, "§c", "speaker.protected", group.name());
            return null;
        }
        return block;
    }

    private void reapply(Block block) {
        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(block);
        if (group == null) {
            plugin.getAudioPlayerManager().applySpeakerSettings(block);
        } else {
            plugin.getAudioPlayerManager().applySpeakerSettings(group.main().getBlock(), block);
        }
    }

    private void list(Player player) {
        List<SpeakerGroup> all = new ArrayList<>(plugin.getSpeakerGroupManager().all());
        if (all.isEmpty()) {
            msg(player, "§7", "command.pair.list_empty");
            return;
        }
        msg(player, "§a", "command.pair.list_header", String.valueOf(all.size()));
        for (SpeakerGroup group : all) {
            player.sendMessage("§7- §f" + group.name() + " §7("
                    + plugin.getMessageManager().get(player, "command.pair.list_entry",
                    String.valueOf(group.size()), describe(group.main())) + ")");
        }
    }

    private void info(Player player) {
        Block block = targetJukebox(player);
        if (block == null) {
            msg(player, "§c", "command.pair.no_jukebox");
            return;
        }

        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(block);
        if (group == null) {
            msg(player, "§7", "command.pair.not_paired");
            return;
        }

        String role = plugin.getMessageManager().get(player,
                group.isMain(block.getLocation()) ? "command.pair.role_main" : "command.pair.role_speaker");
        msg(player, "§a", "command.pair.info", group.name(), role, String.valueOf(group.size()));
    }

    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return SUBS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).sorted().toList();
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("channel")) {
            return java.util.Arrays.stream(SpeakerSettings.Channel.values())
                    .map(SpeakerSettings.Channel::key)
                    .filter(c -> c.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("volume")) {
            return List.of("0", "25", "50", "75", "100");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("add")) {
            return plugin.getSpeakerGroupManager().all().stream()
                    .map(SpeakerGroup::name)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
        }
        return List.of();
    }

    private Block targetJukebox(Player player) {
        Block looking;
        try {
            looking = player.getTargetBlockExact(TARGET_RANGE);
        } catch (Exception e) {
            looking = null;
        }
        return looking != null && looking.getType() == Material.JUKEBOX ? looking : null;
    }

    private static String describe(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private void msg(Player player, String colour, String key, String... args) {
        player.sendMessage(colour + plugin.getMessageManager().get(player, key, (Object[]) args));
    }

    private void ok(Player player, String key, String... args) {
        Chat.actionBar(player, "§a" + plugin.getMessageManager().get(player, key, (Object[]) args));
    }
}
