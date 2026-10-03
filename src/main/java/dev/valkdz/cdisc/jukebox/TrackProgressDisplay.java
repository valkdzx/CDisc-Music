package dev.valkdz.cdisc.jukebox;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.config.PlayerPrefs;
import dev.valkdz.cdisc.config.SneakMode;
import dev.valkdz.cdisc.disc.ItemUtils;
import dev.valkdz.cdisc.util.Chat;
import dev.valkdz.cdisc.util.Tasks;
import dev.valkdz.cdisc.util.TimeUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TrackProgressDisplay implements Listener {

    private static final int MAX_TARGET_DISTANCE = 6;

    private static final int SIGHT_REFRESH_PASSES = 4;

    private static final int HINT_RESEND_PASSES = 8;

    private static final double PROGRESS_STEPS = 200;

    private static final int HINT_RED = 0xFF;
    private static final int HINT_GREEN = 0xD9;
    private static final int HINT_BLUE = 0x66;

    private final Set<UUID> watching = ConcurrentHashMap.newKeySet();

    private static final class Bar {

        final BossBar boss;

        String author;
        String title;
        long second;
        long duration;
        boolean live;
        double progress = -1;

        Bar(BossBar boss) {
            this.boss = boss;
        }

        boolean differs(PlaybackManager.PlaybackInfo info, long second) {
            return this.second != second || duration != info.duration() || live != info.live()
                    || !info.title().equals(title) || !info.author().equals(author);
        }

        void remember(PlaybackManager.PlaybackInfo info, long second) {
            this.author = info.author();
            this.title = info.title();
            this.second = second;
            this.duration = info.duration();
            this.live = info.live();
        }
    }

    private final Map<UUID, Bar> bars = new ConcurrentHashMap<>();

    private final Map<UUID, Block> watchedBlocks = new ConcurrentHashMap<>();

    private final Map<UUID, Integer> hintAge = new ConcurrentHashMap<>();

    private final Set<UUID> sneakHeld = ConcurrentHashMap.newKeySet();

    private final Set<UUID> crouching = ConcurrentHashMap.newKeySet();

    private static final class Sight {

        World world;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        Block block;
        int age;

        boolean sees(Location eye) {
            return eye.getWorld() == world && eye.getX() == x && eye.getY() == y && eye.getZ() == z
                    && eye.getYaw() == yaw && eye.getPitch() == pitch;
        }

        void remember(Location eye, Block found) {
            world = eye.getWorld();
            x = eye.getX();
            y = eye.getY();
            z = eye.getZ();
            yaw = eye.getYaw();
            pitch = eye.getPitch();
            block = found;
            age = 1;
        }
    }

    private final Map<UUID, Sight> sights = new ConcurrentHashMap<>();

    private final Main plugin;

    private Tasks.Handle task;

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

    public SneakMode sneakMode(Player player) {
        return PlayerPrefs.sneakMode(player, plugin.cdiscConfig().getSneakMode());
    }

    @EventHandler
    public void onToggleSneak(PlayerToggleSneakEvent e) {
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();

        if (!e.isSneaking()) {
            crouching.remove(id);
            if (sneakHeld.contains(id)) stopWatching(player);
            return;
        }
        crouching.add(id);

        SneakMode mode = sneakMode(player);
        if (mode == SneakMode.OFF) return;

        Block target = raycast(player);
        if (!isPlayableJukebox(target)) return;

        if (mode == SneakMode.RELEASE) {
            hold(id, target);
            return;
        }

        if (!watching.remove(id)) {
            watching.add(id);
            watchedBlocks.put(id, target);
        } else {
            dropState(id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        stopWatching(player);
        crouching.remove(id);
        sights.remove(id);
        PlayerPrefs.forget(id);
    }

    private void hold(UUID id, Block block) {
        watching.add(id);
        watchedBlocks.put(id, block);
        sneakHeld.add(id);
    }

    private Block raycast(Player player) {
        try {
            return player.getTargetBlockExact(MAX_TARGET_DISTANCE);
        } catch (Exception e) {
            return null;
        }
    }

    // A player standing still keeps the block they looked at, but the ray is cast again
    // every SIGHT_REFRESH_PASSES anyway, or a jukebox placed in front of them stays unseen.
    private Block look(Player player) {
        Location eye = player.getEyeLocation();
        Sight sight = sights.computeIfAbsent(player.getUniqueId(), id -> new Sight());
        if (sight.age > 0 && sight.age < SIGHT_REFRESH_PASSES && sight.sees(eye)) {
            sight.age++;
            return sight.block;
        }
        sight.remember(eye, raycast(player));
        return sight.block;
    }

    // A release-mode player can be crouching before they look at the jukebox, and
    // the sneak event has already been and gone by then.
    private void pickUpHeldSneaks() {
        for (UUID id : crouching) {
            if (watching.contains(id)) continue;

            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                crouching.remove(id);
                continue;
            }
            if (sneakMode(player) != SneakMode.RELEASE) continue;

            Tasks.onEntity(plugin, player, () -> {
                if (!player.isSneaking()) {
                    crouching.remove(id);
                    return;
                }
                Block target = look(player);
                if (isPlayingJukebox(plugin.getAudioPlayerManager(), target)) {
                    hold(id, target);
                }
            });
        }
    }

    private void run() {
        if (!crouching.isEmpty()) pickUpHeldSneaks();
        if (watching.isEmpty()) return;

        PlaybackManager apm = plugin.getAudioPlayerManager();

        for (UUID id : Set.copyOf(watching)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                watching.remove(id);
                dropState(id);
                continue;
            }

            Tasks.onEntity(plugin, player, () -> follow(apm, id, player));
        }
    }

    private void follow(PlaybackManager apm, UUID id, Player player) {
        if (sneakHeld.contains(id) && !player.isSneaking()) {
            stopWatching(player);
            return;
        }

        Block target = look(player);
        boolean lookingAtPlayer = isPlayingJukebox(apm, target);
        if (lookingAtPlayer) {
            watchedBlocks.put(id, target);
        }

        Block block = watchedBlocks.get(id);
        if (block == null) {

            clearHint(id, player);
            return;
        }

        PlaybackManager.PlaybackInfo info = apm.getPlaybackInfo(block);
        if (info == null) {

            if (!apm.hasActiveSession(block)) {
                stopWatching(player);
            }
            return;
        }

        if (!apm.canHear(block, player.getLocation())) {
            clearHint(id, player);
            stopWatching(player);
            return;
        }

        updateBossBar(player, info);

        if (lookingAtPlayer) {
            showHint(id, player);
        } else {

            clearHint(id, player);
        }
    }

    // getState() copies the jukebox with all its NBT, too much for every few ticks: a jukebox
    // that is playing for CDisc holds a CDisc disc, so its session is proof enough.
    private static boolean isPlayingJukebox(PlaybackManager apm, Block target) {
        return target != null && target.getType() == Material.JUKEBOX && apm.hasActiveSession(target);
    }

    private boolean isPlayableJukebox(Block target) {
        if (target == null || target.getType() != Material.JUKEBOX) return false;
        if (!(target.getState() instanceof Jukebox jukebox) || !jukebox.hasRecord()) return false;
        return ItemUtils.isCdiscDisc(jukebox.getRecord());
    }

    private void updateBossBar(Player player, PlaybackManager.PlaybackInfo info) {
        UUID id = player.getUniqueId();
        long second = info.position() / 1000;
        BarColor color = info.live() ? BarColor.RED : BarColor.WHITE;

        Bar bar = bars.get(id);
        boolean changed = bar == null || bar.differs(info, second);
        String title = changed ? title(player, info) : null;

        if (bar == null) {
            bar = new Bar(Bukkit.createBossBar(title, color, BarStyle.SOLID));
            bars.put(id, bar);
            bar.boss.addPlayer(player);
        } else {
            if (changed) bar.boss.setTitle(title);
            if (bar.boss.getColor() != color) bar.boss.setColor(color);
        }
        if (changed) bar.remember(info, second);

        double progress = info.live() ? 1.0
                : Math.rint(progressRatio(info.position(), info.duration()) * PROGRESS_STEPS) / PROGRESS_STEPS;
        if (progress != bar.progress) {
            bar.boss.setProgress(progress);
            bar.progress = progress;
        }
    }

    private String title(Player player, PlaybackManager.PlaybackInfo info) {
        if (info.live()) {
            return plugin.getMessageManager().track(player, "bossbar.title_live", 0,
                    info.author(), info.title());
        }
        String progress = TimeUtils.formatProgress(info.position(), info.duration());
        return plugin.getMessageManager().track(player, "bossbar.title", 0,
                info.author(), info.title(), progress);
    }

    // The client keeps an action bar line about 3 s, so it is only resent before it fades.
    private void showHint(UUID id, Player player) {
        Integer age = hintAge.get(id);
        if (age != null && age < HINT_RESEND_PASSES) {
            hintAge.put(id, age + 1);
            return;
        }
        String hint = plugin.getMessageManager().get(player, "actionbar.open_hint");
        Chat.actionBar(player, hint, HINT_RED, HINT_GREEN, HINT_BLUE);
        hintAge.put(id, 1);
    }

    private void clearHint(UUID id, Player player) {
        if (hintAge.remove(id) != null) {
            Chat.clearActionBar(player);
        }
    }

    private double progressRatio(long position, long duration) {
        if (duration <= 0 || duration == Long.MAX_VALUE) return 0.0;
        double ratio = position / (double) duration;
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    private void dropState(UUID id) {
        Bar bar = bars.remove(id);
        if (bar != null) bar.boss.removeAll();
        watchedBlocks.remove(id);
        hintAge.remove(id);
        sneakHeld.remove(id);
    }

    public void clearAll() {
        for (Bar bar : bars.values()) {
            bar.boss.removeAll();
        }
        bars.clear();
        watchedBlocks.clear();
        hintAge.clear();
        sneakHeld.clear();
        watching.clear();
        crouching.clear();
        sights.clear();
    }

    public void start() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.isSneaking()) crouching.add(player.getUniqueId());
        }
        task = Tasks.globalTimer(plugin, this::run, 1L, 5L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
