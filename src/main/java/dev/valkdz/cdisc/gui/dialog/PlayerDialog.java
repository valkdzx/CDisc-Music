package dev.valkdz.cdisc.gui.dialog;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.audio.queue.RepeatMode;
import dev.valkdz.cdisc.gui.PlayerActions;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.speaker.SpeakerSettings;
import dev.valkdz.cdisc.util.BeaconUtils;
import dev.valkdz.cdisc.util.PlayerPrefs;
import dev.valkdz.cdisc.util.TimeUtils;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PlayerDialog {

    static final int BUTTON_WIDTH = 32;

    private static final int COLUMNS = 6;

    private static final int PROGRESS_BARS = 20;

    private static final ClickCallback.Options CLICKS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofSeconds(30))
            .build();

    private static final Map<UUID, BukkitTask> WATCHING = new HashMap<>();

    private PlayerDialog() {
    }

    static void open(Main plugin, Player player, Block block) {
        stop(player);
        if (!draw(plugin, player, block)) return;

        long every = Math.max(1, plugin.cdiscConfig().getPlayerDialogRefreshTicks());
        WATCHING.put(player.getUniqueId(), Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                stop(player);
                return;
            }
            if (!draw(plugin, player, block)) stop(player);
        }, every, every));
    }

    static void stop(Player player) {
        BukkitTask task = WATCHING.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }

    private static void leave(Player player) {
        stop(player);
        audience(player).closeDialog();
    }

    static Audience audience(Player player) {
        return (Audience) player;
    }

    private static boolean draw(Main plugin, Player player, Block block) {
        LavaPlayerManager apm = plugin.getAudioPlayerManager();

        if (!plugin.getPlayerGuiManager().canOpen(apm, block)) {
            audience(player).closeDialog();
            player.sendMessage("§c" + plugin.getMessageManager()
                    .get(player, "command.player.no_jukebox"));
            return false;
        }

        LavaPlayerManager.PlaybackInfo info = apm.getPlaybackInfo(block);

        DialogBase base = DialogBase.builder(title(plugin, player, info))

                .canCloseWithEscape(false)

                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body(plugin, player, block, info))
                .build();

        ActionButton exit = button(plugin, player, "close",
                (view, listener) -> onMainThread(plugin, () -> leave(player)));

        List<ActionButton> buttons = buttons(plugin, player, block, info);

        audience(player).showDialog(Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(buttons)
                        .exitAction(exit)
                        .columns(COLUMNS)
                        .build())));
        return true;
    }

    private static boolean may(Main plugin, Player player, Action action) {
        return plugin.getPermissions().allows(player, action);
    }

    private static Component title(Main plugin, Player player,
                                   LavaPlayerManager.PlaybackInfo info) {
        if (info == null) return text(plugin, player, "gui.title");
        return legacy("§f§l" + info.title() + " §r§7— " + info.author());
    }

    private static List<DialogBody> body(Main plugin, Player player, Block block,
                                         LavaPlayerManager.PlaybackInfo info) {
        List<DialogBody> lines = new ArrayList<>();

        if (info == null) {
            lines.add(message(text(plugin, player, "gui.info.idle")));
        } else {
            lines.add(message(legacy(progress(plugin, player, info))));
        }

        lines.add(message(legacy(state(plugin, player, block))));
        return lines;
    }

    private static String progress(Main plugin, Player player,
                                   LavaPlayerManager.PlaybackInfo info) {
        if (info.live()) return plugin.getMessageManager().get(player, "gui.title_live");

        String position = TimeUtils.formatCompact(info.position());
        long total = info.duration();
        if (total <= 0) return "§7" + position;

        int filled = (int) Math.round(PROGRESS_BARS
                * Math.min(1.0, Math.max(0.0, (double) info.position() / total)));
        return "§7" + position + "  §a" + "|".repeat(filled)
                + "§8" + "|".repeat(PROGRESS_BARS - filled)
                + "  §7" + TimeUtils.formatCompact(total);
    }

    private static String state(Main plugin, Player player, Block block) {
        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        SpeakerSettings speaker = SpeakerSettings.of(block);

        return plugin.getMessageManager().get(player, "gui.dialog.state",
                plugin.getMessageManager().get(player, repeatKey(apm.getRepeatMode(block))),
                plugin.getMessageManager().get(player, apm.isShuffle(block)
                        ? "gui.dialog.state_shuffle_on" : "gui.dialog.state_shuffle_off"),
                String.valueOf(speaker.volume()),
                String.valueOf(PlayerPrefs.effectiveLocalVolume(player, speaker.volume())));
    }

    private static List<ActionButton> buttons(Main plugin, Player player, Block block,
                                              LavaPlayerManager.PlaybackInfo info) {
        PlayerActions actions = plugin.getPlayerActions();
        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        List<ActionButton> buttons = new ArrayList<>();

        boolean playing = info != null;
        boolean seekable = playing && !info.live();

        if (may(plugin, player, playing ? Action.PLAYER_PREVIOUS : Action.PLAYER_PLAY)) {
            buttons.add(act(plugin, player, block, "prev", () -> {
                if (playing) actions.skipToPrevious(block); else actions.startFromIdle(block, -1);
            }));
        }
        if (may(plugin, player, playing ? Action.PLAYER_PAUSE : Action.PLAYER_PLAY)) {
            buttons.add(act(plugin, player, block,
                    playing && !info.paused() ? "pause" : "play", () -> {
                        if (playing) actions.togglePause(block); else actions.startFromIdle(block, 0);
                    }));
        }
        if (may(plugin, player, playing ? Action.PLAYER_NEXT : Action.PLAYER_PLAY)) {
            buttons.add(act(plugin, player, block, "next", () -> {
                if (playing) actions.skipToNext(block); else actions.startFromIdle(block, 1);
            }));
        }

        if (seekable && may(plugin, player, Action.PLAYER_SEEK)) {
            buttons.add(act(plugin, player, block, "seek_back",
                    () -> actions.seek(player, block, false)));
            buttons.add(act(plugin, player, block, "seek_forward",
                    () -> actions.seek(player, block, true)));
        }

        if (may(plugin, player, Action.PLAYER_REPEAT)) {
            buttons.add(act(plugin, player, block, repeatIcon(apm.getRepeatMode(block)),
                    () -> actions.cycleRepeat(block)));
        }
        if (may(plugin, player, Action.PLAYER_SHUFFLE)) {
            buttons.add(act(plugin, player, block,
                    apm.isShuffle(block) ? "shuffle_on" : "shuffle_off",
                    () -> actions.toggleShuffle(player, block)));
        }

        buttons.add(button(plugin, player, "options", (view, listener) ->
                onMainThread(plugin, () -> {

                    stop(player);
                    OptionsDialog.open(plugin, player, block);
                })));

        if (may(plugin, player, Action.PLAYER_SCREEN)) {
            buttons.add(leaving(plugin, player, "classic", () -> {
                Dialogs.choose(plugin, player, false);
                plugin.getPlayerGuiManager().open(player, block);
            }));
        }

        if (may(plugin, player, Action.QUEUE_OPEN)) {
            buttons.add(leaving(plugin, player, "queue",
                    () -> actions.openQueue(player, block)));
        }
        if (may(plugin, player, Action.PLAYER_CHANNELS)) {
            buttons.add(leaving(plugin, player, "sound",
                    () -> actions.openSpeakerSettings(player, block)));
        }
        if (plugin.cdiscConfig().isSpeakerGroupEnabled()
                && may(plugin, player, Action.PAIR_CREATE)) {
            buttons.add(leaving(plugin, player, "speakers",
                    () -> actions.openPair(player, block)));
        }
        if (plugin.cdiscConfig().isLyricsEnabled()) {

            if (may(plugin, player, Action.LYRICS_PRESET)) {
                buttons.add(leaving(plugin, player, "look_mine",
                        () -> actions.openMyLyricsLook(player)));
            }
        }
        if (BeaconUtils.maxRangeLevel(BeaconUtils.beaconTierBelow(block)) >= 1
                && may(plugin, player, Action.PLAYER_BEACON)) {
            buttons.add(act(plugin, player, block, "beacon",
                    () -> actions.cycleBeaconLevel(block)));
        }
        if (may(plugin, player, Action.PLAYER_PORTABLE)) {
            buttons.add(leaving(plugin, player, "portable",
                    () -> actions.pickUp(player, block)));
        }
        return buttons;
    }

    private static String repeatIcon(RepeatMode mode) {
        return mode == RepeatMode.OFF ? "repeat_off" : "repeat_on";
    }

    private static String repeatKey(RepeatMode mode) {
        return switch (mode) {
            case QUEUE -> "gui.dialog.state_repeat_queue";
            case TRACK -> "gui.dialog.state_repeat_track";
            default -> "gui.dialog.state_repeat_off";
        };
    }

    private static ActionButton act(Main plugin, Player player, Block block,
                                    String name, Runnable action) {
        return button(plugin, player, name, (view, listener) ->
                onMainThread(plugin, () -> {
                    action.run();
                    draw(plugin, player, block);
                }));
    }

    private static ActionButton leaving(Main plugin, Player player,
                                        String name, Runnable action) {
        return button(plugin, player, name, (view, listener) ->
                onMainThread(plugin, () -> {
                    leave(player);
                    action.run();
                }));
    }

    static ActionButton button(Main plugin, Player player, String name,
                               DialogActionCallback callback) {
        return ActionButton.create(
                text(plugin, player, "gui.dialog.icon_" + name),
                text(plugin, player, "gui.dialog." + name),
                BUTTON_WIDTH,
                DialogAction.customClick(callback, CLICKS));
    }

    static void onMainThread(Main plugin, Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private static DialogBody message(Component component) {
        return DialogBody.plainMessage(component);
    }

    static Component text(Main plugin, Player player, String key, String... args) {
        return legacy(plugin.getMessageManager().get(player, key, (Object[]) args));
    }

    static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text == null ? "" : text);
    }
}
