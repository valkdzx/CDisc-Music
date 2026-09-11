package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.util.TimeUtils;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class PlayerGuiListener implements Listener {

    private final Main plugin;

    public PlayerGuiListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof PlayerGuiHolder holder)) return;
        e.setCancelled(true);

        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        Block block = holder.getBlock();

        boolean playing = apm.hasActiveSession(block);
        if (playing && apm.getGeneration(block) != holder.getGeneration()) {
            player.closeInventory();
            return;
        }
        if (!playing && !plugin.getPlayerGuiManager().canOpen(apm, block)) {
            player.closeInventory();
            return;
        }

        Action needed = actionFor(e.getSlot(), playing, e.isRightClick());
        if (needed != null && !plugin.getPermissions().allows(player, needed)) return;

        PlayerActions actions = plugin.getPlayerActions();
        if (!playing) {
            switch (e.getSlot()) {
                case PlayerGuiManager.SLOT_PAUSE -> actions.startFromIdle(block, 0);
                case PlayerGuiManager.SLOT_NEXT -> actions.startFromIdle(block, 1);
                case PlayerGuiManager.SLOT_PREV -> actions.startFromIdle(block, -1);
                case PlayerGuiManager.SLOT_REPEAT -> actions.cycleRepeat(block);
                case PlayerGuiManager.SLOT_SHUFFLE -> apm.toggleShuffle(block);
                case PlayerGuiManager.SLOT_QUEUE -> actions.openQueue(player, block);
                case PlayerGuiManager.SLOT_EXIT -> player.closeInventory();
                default -> {
                    return;
                }
            }

            if (e.getSlot() != PlayerGuiManager.SLOT_EXIT
                    && e.getSlot() != PlayerGuiManager.SLOT_QUEUE) {
                plugin.getPlayerGuiManager().refresh(player, block);
            }
            return;
        }

        boolean stateChanged = true;
        switch (e.getSlot()) {
            case PlayerGuiManager.SLOT_INFO -> {
                stateChanged = false;

                if (!apm.isLive(block)) player.closeInventory();
                actions.enterChatSeek(player, block);
            }
            case PlayerGuiManager.SLOT_SEEK_BACK -> stateChanged = actions.seek(player, block, false);
            case PlayerGuiManager.SLOT_SEEK_FORWARD -> stateChanged = actions.seek(player, block, true);
            case PlayerGuiManager.SLOT_PREV -> actions.skipToPrevious(block);
            case PlayerGuiManager.SLOT_NEXT -> actions.skipToNext(block);
            case PlayerGuiManager.SLOT_PAUSE -> actions.togglePause(block);
            case PlayerGuiManager.SLOT_REPEAT -> actions.cycleRepeat(block);
            case PlayerGuiManager.SLOT_VOLUME ->
                    actions.stepSpeakerVolume(block, !e.isRightClick());
            case PlayerGuiManager.SLOT_LOCAL_VOLUME -> {

                if (e.isShiftClick()) {
                    actions.followJukeboxVolume(player, block);
                } else {
                    actions.stepLocalVolume(player, block, !e.isRightClick());
                }
            }
            case PlayerGuiManager.SLOT_SHUFFLE -> actions.toggleShuffle(player, block);
            case PlayerGuiManager.SLOT_QUEUE -> {
                stateChanged = false;
                actions.openQueue(player, block);
            }
            case PlayerGuiManager.SLOT_BEACON -> {
                if (e.isLeftClick()) {
                    actions.cycleBeaconLevel(block);
                } else {
                    stateChanged = false;
                }
            }
            case PlayerGuiManager.SLOT_PORTABLE -> {
                stateChanged = false;
                if (actions.pickUp(player, block)) player.closeInventory();
            }
            case PlayerGuiManager.SLOT_CHANNELS -> {
                stateChanged = false;
                actions.openSpeakerSettings(player, block);
            }
            case PlayerGuiManager.SLOT_PAIR -> {
                stateChanged = false;
                actions.openPair(player, block);
            }
            case PlayerGuiManager.SLOT_LYRICS -> {
                if (!plugin.cdiscConfig().isLyricsEnabled()) {
                    stateChanged = false;
                    break;
                }

                if (e.isRightClick()) {
                    stateChanged = false;
                    actions.openLyricsLook(player, block);
                    break;
                }
                actions.toggleLyrics(player, block);
            }
            case PlayerGuiManager.SLOT_TRACK_MESSAGES -> actions.toggleTrackMessages(player);
            case PlayerGuiManager.SLOT_EXIT -> player.closeInventory();
            case PlayerGuiManager.SLOT_VIEW -> {
                stateChanged = false;

                dev.valkdz.cdisc.gui.dialog.Dialogs.choose(plugin, player, true);
                player.closeInventory();
                plugin.getPlayerGuiManager().open(player, block);
            }
            default -> stateChanged = false;
        }

        if (stateChanged && e.getSlot() != PlayerGuiManager.SLOT_EXIT) {
            plugin.getPlayerGuiManager().refresh(player, block);
        }
    }

    private static Action actionFor(int slot, boolean playing, boolean rightClick) {
        return switch (slot) {
            case PlayerGuiManager.SLOT_QUEUE -> Action.QUEUE_OPEN;
            case PlayerGuiManager.SLOT_INFO, PlayerGuiManager.SLOT_SEEK_BACK,
                 PlayerGuiManager.SLOT_SEEK_FORWARD -> Action.PLAYER_SEEK;
            case PlayerGuiManager.SLOT_PREV ->
                    playing ? Action.PLAYER_PREVIOUS : Action.PLAYER_PLAY;
            case PlayerGuiManager.SLOT_NEXT -> playing ? Action.PLAYER_NEXT : Action.PLAYER_PLAY;
            case PlayerGuiManager.SLOT_PAUSE -> playing ? Action.PLAYER_PAUSE : Action.PLAYER_PLAY;
            case PlayerGuiManager.SLOT_REPEAT -> Action.PLAYER_REPEAT;
            case PlayerGuiManager.SLOT_SHUFFLE -> Action.PLAYER_SHUFFLE;
            case PlayerGuiManager.SLOT_VOLUME -> Action.PLAYER_VOLUME;
            case PlayerGuiManager.SLOT_LOCAL_VOLUME -> Action.PLAYER_LOCAL_VOLUME;
            case PlayerGuiManager.SLOT_BEACON -> Action.PLAYER_BEACON;
            case PlayerGuiManager.SLOT_PORTABLE -> Action.PLAYER_PORTABLE;
            case PlayerGuiManager.SLOT_CHANNELS -> Action.PLAYER_CHANNELS;
            case PlayerGuiManager.SLOT_TRACK_MESSAGES -> Action.PLAYER_MESSAGES;
            case PlayerGuiManager.SLOT_LYRICS ->
                    rightClick ? Action.LYRICS_LOOK : Action.LYRICS_TOGGLE;
            case PlayerGuiManager.SLOT_VIEW -> Action.PLAYER_SCREEN;

            // Exit closes the screen, and the pair button is judged by the screen it opens.
            default -> null;
        };
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof PlayerGuiHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof PlayerGuiHolder holder && e.getPlayer() instanceof Player player) {
            plugin.getPlayerGuiManager().onClosed(player, holder.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        Block block = plugin.getPlayerGuiManager().getAwaitingSeekChat(player);
        if (block == null) return;

        e.setCancelled(true);
        plugin.getPlayerGuiManager().exitChatSeekMode(player);

        String typed = e.getMessage().trim();
        if (typed.equals(".")) {
            return;
        }

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        if (apm.isLive(block)) {
            player.sendMessage(plugin.getMessageManager().get(player, "gui.seek.live_blocked"));
            return;
        }
        Long ms = TimeUtils.parseTimecode(typed);
        if (ms != null && apm.hasActiveSession(block)) {
            apm.seekTo(block, ms);
            player.sendMessage(plugin.getMessageManager().get(player, "gui.seek.success", TimeUtils.format(ms)));
        } else {
            player.sendMessage(plugin.getMessageManager().get(player, "gui.seek.invalid"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getPlayerGuiManager().exitChatSeekMode(e.getPlayer());
    }
}
