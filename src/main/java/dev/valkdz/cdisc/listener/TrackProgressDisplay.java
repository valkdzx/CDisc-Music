package dev.valkdz.cdisc.listener;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.util.ItemUtils;
import dev.valkdz.cdisc.util.Chat;
import dev.valkdz.cdisc.util.TimeUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TrackProgressDisplay extends BukkitRunnable implements Listener {

    private static final int MAX_TARGET_DISTANCE = 6;

    private static final int HINT_RED = 0xFF;
    private static final int HINT_GREEN = 0xD9;
    private static final int HINT_BLUE = 0x66;

    private final Set<UUID> watching = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();

    private final Map<UUID, Block> watchedBlocks = new ConcurrentHashMap<>();

    private final Set<UUID> showingHint = ConcurrentHashMap.newKeySet();
    private final Main plugin;

    public TrackProgressDisplay(Main plugin) {
        this.plugin = plugin;
    }

    public boolean isWatching(Player player) {
        return watching.contains(player.getUniqueId());
    }

    public boolean toggleWatching(Player player, Block block) {
        UUID id = player.getUniqueId();
        if (watching.remove(id)) {
            dropState(id);
            return false;
        }
        watching.add(id);
        watchedBlocks.put(id, block);
        return true;
    }

    public void stopWatching(Player player) {
        watching.remove(player.getUniqueId());
        dropState(player.getUniqueId());
    }

    @EventHandler
    public void onToggleSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;

        Player player = e.getPlayer();
        UUID id = player.getUniqueId();

        Block target;
        try {
            target = player.getTargetBlockExact(MAX_TARGET_DISTANCE);
        } catch (Exception ex) {
            target = null;
        }
        if (!isPlayableJukebox(target)) return;

        if (!watching.remove(id)) {
            watching.add(id);
        } else {
            dropState(id);
        }
    }

    @Override
    public void run() {
        if (watching.isEmpty()) return;

        LavaPlayerManager apm = plugin.getAudioPlayerManager();

        for (UUID id : Set.copyOf(watching)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                watching.remove(id);
                dropState(id);
                continue;
            }

            Block target;
            try {
                target = player.getTargetBlockExact(MAX_TARGET_DISTANCE);
            } catch (Exception e) {
                target = null;
            }

            boolean lookingAtPlayer = isPlayableJukebox(target);
            if (lookingAtPlayer) {
                watchedBlocks.put(id, target);
            }

            Block block = watchedBlocks.get(id);
            if (block == null) {

                clearHint(id, player);
                continue;
            }

            LavaPlayerManager.PlaybackInfo info = apm.getPlaybackInfo(block);
            if (info == null) {

                if (!apm.hasActiveSession(block)) {
                    stopWatching(player);
                }
                continue;
            }

            updateBossBar(player, info);

            if (lookingAtPlayer) {
                showHint(id, player);
            } else {

                clearHint(id, player);
            }
        }
    }

    private boolean isPlayableJukebox(Block target) {
        if (target == null || target.getType() != Material.JUKEBOX) return false;
        if (!(target.getState() instanceof Jukebox jukebox) || !jukebox.hasRecord()) return false;
        return ItemUtils.isCdiscDisc(jukebox.getRecord());
    }

    private void updateBossBar(Player player, LavaPlayerManager.PlaybackInfo info) {
        UUID id = player.getUniqueId();

        String title;
        if (info.live()) {
            title = plugin.getMessageManager().get(player, "bossbar.title_live",
                    info.author(), info.title());
        } else {
            String progress = TimeUtils.formatProgress(info.position(), info.duration());
            title = plugin.getMessageManager().get(player, "bossbar.title",
                    info.author(), info.title(), progress);
        }

        BossBar bar = bossBars.computeIfAbsent(id, key ->
                Bukkit.createBossBar(title, BarColor.WHITE, BarStyle.SOLID));

        bar.setTitle(title);

        bar.setColor(info.live() ? BarColor.RED : BarColor.WHITE);
        bar.setProgress(info.live() ? 1.0 : progressRatio(info.position(), info.duration()));

        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }
        bar.setVisible(true);
    }

    private void showHint(UUID id, Player player) {
        String hint = plugin.getMessageManager().get(player, "actionbar.open_hint");
        Chat.actionBar(player, hint, HINT_RED, HINT_GREEN, HINT_BLUE);
        showingHint.add(id);
    }

    private void clearHint(UUID id, Player player) {
        if (showingHint.remove(id)) {
            Chat.clearActionBar(player);
        }
    }

    private double progressRatio(long position, long duration) {
        if (duration <= 0 || duration == Long.MAX_VALUE) return 0.0;
        double ratio = position / (double) duration;
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    private void dropState(UUID id) {
        BossBar bar = bossBars.remove(id);
        if (bar != null) bar.removeAll();
        watchedBlocks.remove(id);
        showingHint.remove(id);
    }

    public void clearAll() {
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
        }
        bossBars.clear();
        watchedBlocks.clear();
        showingHint.clear();
        watching.clear();
    }

    public void start() {
        runTaskTimer(plugin, 0L, 5L);
    }
}
