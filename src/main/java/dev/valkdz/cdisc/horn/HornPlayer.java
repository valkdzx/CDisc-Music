package dev.valkdz.cdisc.horn;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.util.ItemUtils;
import dev.valkdz.cdisc.voice.VoiceSession;
import dev.valkdz.cdisc.voice.anchor.SoundAnchor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class HornPlayer implements Listener {

    private static final long LOAD_TIMEOUT_MS = 15_000L;

    private final Main plugin;
    private final HornClips clips;
    private final Map<UUID, Blast> blasts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pump = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cdisc-horn-pump");
        thread.setDaemon(true);
        return thread;
    });
    private BukkitTask task;

    private static final class Blast {
        final Player player;
        final SoundAnchor anchor;
        final VoiceSession voice;
        final HornClips.Clip clip;
        final long blownAt = System.currentTimeMillis();
        ScheduledFuture<?> sender;
        int sent;
        volatile boolean started;
        volatile boolean finished;

        Blast(Player player, SoundAnchor anchor, VoiceSession voice, HornClips.Clip clip) {
            this.player = player;
            this.anchor = anchor;
            this.voice = voice;
            this.clip = clip;
        }
    }

    public HornPlayer(Main plugin) {
        this.plugin = plugin;
        this.clips = new HornClips(plugin);
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Blast blast : new ArrayList<>(blasts.values())) end(blast);
        pump.shutdownNow();
        clips.shutdown();
    }

    public void prefetch(ItemStack item) {
        if (!GoatHorns.isRecorded(item) || !plugin.cdiscConfig().isGoatHornEnabled()) return;
        if (!plugin.getAudioPlayerManager().hasVoiceBackend()) return;
        clips.get(ItemUtils.readDiscData(item), GoatHorns.clipOf(plugin, item));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHold(PlayerItemHeldEvent e) {
        prefetch(e.getPlayer().getInventory().getItem(e.getNewSlot()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        prefetch(e.getMainHandItem());
        prefetch(e.getOffHandItem());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        prefetch(e.getPlayer().getInventory().getItemInMainHand());
        prefetch(e.getPlayer().getInventory().getItemInOffHand());
    }

    // RIGHT_CLICK_AIR arrives already cancelled, so cancelled events must still be seen here.
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlow(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.useItemInHand() == Event.Result.DENY) return;

        ItemStack item = e.getItem();
        if (!GoatHorns.isRecorded(item) || !plugin.cdiscConfig().isGoatHornEnabled()) return;
        if (!plugin.getAudioPlayerManager().hasVoiceBackend()) return;

        e.setUseItemInHand(Event.Result.DENY);
        Player player = e.getPlayer();
        silenceVanillaCall(player);
        if (blasts.containsKey(player.getUniqueId()) || player.hasCooldown(Material.GOAT_HORN)) return;

        blow(player, item);
    }

    // The client plays its own horn call before the server hears of the click, so a
    // cancelled event still leaves the blower hearing it until this stop arrives.
    private static void silenceVanillaCall(Player player) {
        for (int i = 0; i < 8; i++) {
            player.stopSound("minecraft:item.goat_horn.sound." + i, SoundCategory.RECORDS);
        }
    }

    private void blow(Player player, ItemStack item) {
        long clipMs = GoatHorns.clipOf(plugin, item);
        HornClips.Clip clip = clips.get(ItemUtils.readDiscData(item), clipMs);
        if (clip.failed()) return;

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        SoundAnchor anchor = apm.getAnchorManager().createAt(player.getLocation());
        VoiceSession voice = apm.createFollowingSession(anchor.entity(),
                (float) plugin.cdiscConfig().getGoatHornDistance());
        if (voice == null) {
            anchor.remove();
            return;
        }
        anchor.setTeleportSmoothing(2);

        Blast blast = new Blast(player, anchor, voice, clip);
        blasts.put(player.getUniqueId(), blast);
        player.setCooldown(Material.GOAT_HORN, (int) Math.max(20L, clipMs / 50L));
        blast.sender = pump.scheduleAtFixedRate(() -> send(blast),
                0, HornClips.FRAME_MS, TimeUnit.MILLISECONDS);
    }

    // Runs on the pump thread; a throw here would cancel every later frame of this blast.
    private void send(Blast blast) {
        try {
            HornClips.Clip clip = blast.clip;
            if (blast.sent >= clip.wanted || (clip.done() && blast.sent >= clip.available())) {
                blast.finished = true;
                return;
            }
            if (blast.sent >= clip.available()) return;

            blast.started = true;
            blast.voice.sendFrame(clip.frame(blast.sent++));
        } catch (RuntimeException e) {
            blast.finished = true;
        }
    }

    private void tick() {
        if (blasts.isEmpty()) return;
        long now = System.currentTimeMillis();

        for (Blast blast : new ArrayList<>(blasts.values())) {
            Player player = blast.player;
            if (blast.finished || !player.isOnline() || player.isDead() || !blast.anchor.isAlive()
                    || !blast.anchor.inWorld(player.getWorld())) {
                end(blast);
                continue;
            }
            blast.anchor.followAt(player.getLocation());

            if (!blast.started && now - blast.blownAt > LOAD_TIMEOUT_MS) end(blast);
        }
    }

    private void end(Blast blast) {
        if (!blasts.remove(blast.player.getUniqueId(), blast)) return;
        if (blast.sender != null) blast.sender.cancel(false);
        blast.voice.close();
        blast.anchor.remove();
    }
}
