package dev.valkdz.cdisc.command;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.audio.queue.RepeatMode;
import dev.valkdz.cdisc.portable.PortableJukeboxManager;
import dev.valkdz.cdisc.util.TimeUtils;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class PlayerSubcommand {

    public static final List<String> SUBS = List.of(
            "gui", "pause", "play", "seek", "repeat", "next", "previous", "queue",
            "status", "info", "scoreboard");

    private static final int TARGET_RANGE = 6;

    private final Main plugin;

    public PlayerSubcommand(Main plugin) {
        this.plugin = plugin;
    }

    public void handle(Player player, String[] args) {
        Block block = targetJukebox(player);
        if (block == null) {
            msg(player, "§c", "command.player.no_jukebox");
            return;
        }

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        String sub = args.length < 2 ? "info" : args[1].toLowerCase(Locale.ROOT);

        boolean needsPlayback = !sub.equals("gui");
        if (needsPlayback && !apm.hasActiveSession(block)) {
            msg(player, "§c", "command.player.not_playing");
            return;
        }
        switch (sub) {

            case "gui" -> plugin.getPlayerGuiManager().open(player, block);
            case "queue" -> plugin.getQueueGuiManager().open(player, block);
            case "pause" -> {
                apm.setPaused(block, true);
                msg(player, "§e", "command.player.paused");
            }
            case "play" -> {
                apm.setPaused(block, false);
                msg(player, "§a", "command.player.resumed");
            }
            case "next" -> apm.skipToNext(block);
            case "previous", "previos", "prev" -> apm.skipToPrevious(block);
            case "seek" -> seek(player, apm, block, args);
            case "repeat" -> repeat(player, apm, block, args);
            case "status" -> {
                boolean on = plugin.getTrackProgressDisplay().toggleWatching(player, block);
                msg(player, on ? "§a" : "§7", on ? "command.player.status_on" : "command.player.status_off");
            }
            case "info" -> info(player, apm, block);
            case "scoreboard" -> scoreboard(player, args);
            default -> msg(player, "§c", "command.player.usage");
        }
    }

    private Block targetJukebox(Player player) {
        PortableJukeboxManager.Carry carry = plugin.getPortableJukeboxManager().carryOf(player);
        if (carry != null) {

            return carry.origin();
        }

        Block looking;
        try {
            looking = player.getTargetBlockExact(TARGET_RANGE);
        } catch (Exception e) {
            looking = null;
        }
        return looking != null && looking.getType() == Material.JUKEBOX ? looking : null;
    }

    private void scoreboard(Player player, String[] args) {
        if (plugin.getPortableJukeboxManager() == null
                || plugin.getPortableJukeboxManager().carryOf(player) == null) {
            msg(player, "§c", "command.player.scoreboard_not_carrying");
            return;
        }

        boolean on = args.length > 2
                ? parseToggle(args[2], dev.valkdz.cdisc.util.PlayerPrefs.showsLyricsScoreboard(player))
                : !dev.valkdz.cdisc.util.PlayerPrefs.showsLyricsScoreboard(player);

        dev.valkdz.cdisc.util.PlayerPrefs.setLyricsScoreboard(player, on);
        if (!on) plugin.getCarriedLyrics().clear(player.getUniqueId());

        msg(player, "§a", on ? "command.player.scoreboard_on" : "command.player.scoreboard_off");
    }

    private static boolean parseToggle(String raw, boolean current) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "enabled", "вкл" -> true;
            case "off", "false", "disable", "disabled", "выкл" -> false;
            default -> !current;
        };
    }

    private void upNext(Player player, LavaPlayerManager apm, Block block) {
        if (apm.isShuffle(block)) {
            player.sendMessage("§7" + plugin.getMessageManager()
                    .get(player, "command.player.up_next_shuffle"));
            return;
        }

        org.bukkit.inventory.ItemStack next = apm.nextDisc(block);
        if (next == null) {
            player.sendMessage("§8" + plugin.getMessageManager()
                    .get(player, "command.player.up_next_nothing"));
            return;
        }

        dev.valkdz.cdisc.util.ItemUtils.DiscData data =
                dev.valkdz.cdisc.util.ItemUtils.readDiscData(next);
        if (data == null || data.title() == null || data.title().isBlank()) {
            player.sendMessage("§7" + plugin.getMessageManager()
                    .get(player, "command.player.up_next_unnamed"));
            return;
        }

        String author = data.author() == null || data.author().isBlank()
                ? plugin.getMessageManager().get(player, "command.player.up_next_unknown_author")
                : data.author();

        player.sendMessage("§7" + plugin.getMessageManager()
                .get(player, "command.player.up_next", author, data.title()));
    }

    private void seek(Player player, LavaPlayerManager apm, Block block, String[] args) {
        if (args.length < 3) {
            msg(player, "§c", "command.player.seek_usage");
            return;
        }
        if (apm.isLive(block)) {
            player.sendMessage(plugin.getMessageManager().get(player, "gui.seek.live_blocked"));
            return;
        }

        String raw = args[2];
        boolean relative = raw.startsWith("+") || raw.startsWith("-");

        if (relative) {
            Long offset = TimeUtils.parseTimecode(raw.substring(1));
            if (offset == null) {
                msg(player, "§c", "command.player.seek_invalid");
                return;
            }
            long delta = raw.startsWith("-") ? -offset : offset;
            apm.seek(block, delta);
        } else {
            Long target = TimeUtils.parseTimecode(raw);
            if (target == null) {
                msg(player, "§c", "command.player.seek_invalid");
                return;
            }
            apm.seekTo(block, target);
        }

        LavaPlayerManager.PlaybackInfo info = apm.getPlaybackInfo(block);
        String at = info == null ? raw : TimeUtils.format(info.position());
        player.sendMessage("§a" + plugin.getMessageManager().get(player, "gui.seek.success", at));
    }

    private void repeat(Player player, LavaPlayerManager apm, Block block, String[] args) {
        RepeatMode mode;
        if (args.length < 3) {
            mode = apm.cycleRepeat(block);
        } else {
            mode = switch (args[2].toLowerCase(Locale.ROOT)) {
                case "off" -> RepeatMode.OFF;
                case "track" -> RepeatMode.TRACK;
                case "queue" -> RepeatMode.QUEUE;
                default -> null;
            };
            if (mode == null) {
                msg(player, "§c", "command.player.repeat_usage");
                return;
            }
            apm.setRepeatMode(block, mode);
        }

        String name = plugin.getMessageManager().get(player, "command.player.repeat_" + mode.name().toLowerCase(Locale.ROOT));
        player.sendMessage("§a" + plugin.getMessageManager().get(player, "command.player.repeat_set", name));
    }

    private void info(Player player, LavaPlayerManager apm, Block block) {
        LavaPlayerManager.PlaybackInfo info = apm.getPlaybackInfo(block);
        if (info == null) {
            msg(player, "§c", "command.player.not_playing");
            return;
        }

        String progress = info.live()
                ? plugin.getMessageManager().get(player, "command.player.info_live")
                : TimeUtils.formatProgress(info.position(), info.duration());
        player.sendMessage("§a" + plugin.getMessageManager()
                .get(player, "command.player.info", info.author(), info.title(), progress));

        upNext(player, apm, block);
    }

    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return SUBS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).sorted().toList();
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("scoreboard")) {
            return java.util.stream.Stream.of("on", "off")
                    .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("repeat")) {
            return Stream.of("off", "track", "queue")
                    .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("seek")) {
            return List.of("MM:SS", "+30", "-30");
        }
        return List.of();
    }

    private void msg(Player player, String colour, String key) {
        player.sendMessage(colour + plugin.getMessageManager().get(player, key));
    }
}
