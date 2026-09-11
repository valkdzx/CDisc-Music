package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.audio.queue.DiscQueue;
import dev.valkdz.cdisc.audio.queue.RepeatMode;
import dev.valkdz.cdisc.lyrics.LyricsPrefs;
import dev.valkdz.cdisc.speaker.SpeakerSettings;
import dev.valkdz.cdisc.util.BeaconUtils;
import dev.valkdz.cdisc.util.Chat;
import dev.valkdz.cdisc.util.PlayerPrefs;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class PlayerActions {

    private final Main plugin;

    public PlayerActions(Main plugin) {
        this.plugin = plugin;
    }

    private LavaPlayerManager audio() {
        return plugin.getAudioPlayerManager();
    }

    private String message(Player player, String key) {
        return plugin.getMessageManager().get(player, key);
    }

    public void startFromIdle(Block block, int direction) {
        LavaPlayerManager apm = audio();
        DiscQueue queue = apm.getQueue(block);

        if (queue != null && !queue.isEmpty()) {
            int current = queue.getCurrentIndex();
            int index = switch (direction) {
                case 1 -> queue.nextFilledAfter(current);
                case -1 -> queue.prevFilledBefore(current);
                default -> current >= 0 && queue.getSlot(current) != null
                        ? current : queue.firstFilled();
            };

            if (index < 0) index = direction < 0 ? queue.lastFilled() : queue.firstFilled();

            if (index >= 0) {
                apm.playQueueEntry(block, index);
                return;
            }
        }

        apm.playInsertedDisc(block);
    }

    public void togglePause(Block block) {
        audio().togglePause(block);
    }

    public void skipToNext(Block block) {
        audio().skipToNext(block);
    }

    public void skipToPrevious(Block block) {
        audio().skipToPrevious(block);
    }

    public void cycleRepeat(Block block) {
        audio().cycleRepeat(block);
    }

    public void setRepeat(Block block, String mode) {
        for (RepeatMode candidate : RepeatMode.values()) {
            if (candidate.name().equals(mode)) {
                audio().setRepeatMode(block, candidate);
                return;
            }
        }
    }

    public boolean seek(Player player, Block block, boolean forward) {
        if (audio().isLive(block)) {
            player.sendMessage(message(player, "gui.seek.live_blocked"));
            return false;
        }
        audio().seek(block, forward
                ? PlayerGuiManager.seekStepMs() : -PlayerGuiManager.seekStepMs());
        return true;
    }

    public boolean seekTo(Player player, Block block, long positionMs) {
        if (audio().isLive(block)) {
            player.sendMessage(message(player, "gui.seek.live_blocked"));
            return false;
        }
        audio().seekTo(block, Math.max(0, positionMs));
        return true;
    }

    public boolean seekToTimecode(Player player, Block block, String text) {
        Long position = dev.valkdz.cdisc.util.TimeUtils.parseTimecode(text);
        if (position == null) {
            player.sendMessage(message(player, "gui.seek.invalid"));
            return false;
        }
        return seekTo(player, block, position);
    }

    public boolean enterChatSeek(Player player, Block block) {
        if (audio().isLive(block)) {
            player.sendMessage(message(player, "gui.seek.live_blocked"));
            return false;
        }
        plugin.getPlayerGuiManager().enterChatSeekMode(player, block);
        return true;
    }

    public void toggleShuffle(Player player, Block block) {
        boolean on = audio().toggleShuffle(block);
        Chat.actionBar(player, "§a" + message(player,
                on ? "gui.shuffle_on.name" : "gui.shuffle_off.name"));
    }

    public void stepSpeakerVolume(Block block, boolean up) {
        SpeakerSettings settings = SpeakerSettings.of(block);
        int step = up ? SpeakerSettings.VOLUME_STEP : -SpeakerSettings.VOLUME_STEP;
        SpeakerSettings.store(block, settings.withVolume(
                SpeakerSettings.clampVolume(settings.volume() + step)));
        audio().applySpeakerSettings(block);
    }

    public void stepLocalVolume(Player player, Block block, boolean up) {
        int now = PlayerPrefs.effectiveLocalVolume(player, SpeakerSettings.of(block).volume());
        int step = up ? SpeakerSettings.VOLUME_STEP : -SpeakerSettings.VOLUME_STEP;
        PlayerPrefs.setLocalVolume(player, SpeakerSettings.clampVolume(now + step));
        audio().applyCarrierVolume(block);
    }

    public void setSpeakerVolume(Block block, int volume) {
        SpeakerSettings settings = SpeakerSettings.of(block);
        SpeakerSettings.store(block, settings.withVolume(SpeakerSettings.clampVolume(volume)));
        audio().applySpeakerSettings(block);
    }

    public void setLocalVolume(Player player, Block block, int volume) {
        PlayerPrefs.setLocalVolume(player, SpeakerSettings.clampVolume(volume));
        audio().applyCarrierVolume(block);
    }

    public void followJukeboxVolume(Player player, Block block) {
        PlayerPrefs.setLocalVolume(player, PlayerPrefs.VOLUME_FOLLOWS_JUKEBOX);
        audio().applyCarrierVolume(block);
    }

    public void cycleBeaconLevel(Block block) {
        int maxLevel = BeaconUtils.maxRangeLevel(BeaconUtils.beaconTierBelow(block));
        if (maxLevel < 1) return;

        int next = audio().getBeaconRangeLevel(block) + 1;
        if (next > maxLevel) next = 0;
        audio().setBeaconRangeLevel(block, next);
    }

    public boolean pickUp(Player player, Block block) {
        if (plugin.getPortableJukeboxManager().pickUp(player, block)) return true;
        player.sendMessage("§c" + message(player, "portable.cannot_pick_up"));
        return false;
    }

    public void toggleTrackMessages(Player player) {
        setTrackMessages(player, !PlayerPrefs.showsTrackMessages(player));
    }

    public void setTrackMessages(Player player, boolean on) {
        PlayerPrefs.setTrackMessages(player, on);
        Chat.actionBar(player, "§a" + message(player,
                on ? "gui.track_messages.enabled" : "gui.track_messages.disabled"));
    }

    public void toggleLyrics(Player player, Block block) {
        if (!plugin.cdiscConfig().isLyricsEnabled()) return;
        setLyrics(player, block, !LyricsPrefs.isEnabled(block));
    }

    public void setLyrics(Player player, Block block, boolean on) {
        if (!plugin.cdiscConfig().isLyricsEnabled()) return;

        LyricsPrefs.setEnabled(block, on);
        if (!on) plugin.getLyricsDisplay().clear(block);
        Chat.actionBar(player, "§a" + message(player,
                on ? "gui.lyrics.enabled" : "gui.lyrics.disabled"));

        if (on) plugin.getLyricsDisplay().announce(player, block);
    }

    public void openQueue(Player player, Block block) {
        plugin.getQueueGuiManager().open(player, block);
    }

    public void openLyricsLook(Player player, Block block) {
        if (!plugin.cdiscConfig().isLyricsEnabled()) return;
        plugin.getLyricsGuiManager().open(player, block);
    }

    public void openMyLyricsLook(Player player) {
        if (!plugin.cdiscConfig().isLyricsEnabled()) return;
        plugin.getLyricsGuiManager().openPreset(player);
    }

    public void openSpeakerSettings(Player player, Block block) {
        if (plugin.getSpeakerGroupManager().groupAt(block) != null) return;
        plugin.getPairGuiManager().openSettings(player, block, block);
    }

    public void openPair(Player player, Block block) {
        if (!plugin.cdiscConfig().isSpeakerGroupEnabled()) return;
        if (plugin.getSpeakerGroupManager().groupAt(block) != null) {
            plugin.getPairGuiManager().openManage(player, block);
        } else {
            plugin.getPairGuiManager().promptForName(player, block);
        }
    }
}
