package dev.valkdz.cdisc.jukebox;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.api.event.PlaybackStopEvent;
import dev.valkdz.cdisc.api.event.TrackStartEvent;
import dev.valkdz.cdisc.jukebox.packet.WorldEventPacketInterceptor;
import dev.valkdz.cdisc.util.Chat;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ParrotDance implements Listener {

    private static final int RECORD_START = 1010;
    private static final int RECORD_STOP = 1011;
    private static final double RANGE = 64.0;
    private static final double PARROT_REACH = 3.0;

    // Its first half second is silence, so a stop every 4 ticks cuts it before anything is
    // audible; a disc that starts loud would leak, since the client opens the stream async.
    private static final Material SILENT_START_DISC = Material.MUSIC_DISC_PIGSTEP;
    private static final String SILENT_START_SOUND = "minecraft:music_disc.pigstep";
    private static final long MUTE_PERIOD = 4L;
    private static final long MUTE_FOR = 60L;

    private record Pass(UUID player, int effectId, int x, int y, int z) {}

    private final Main plugin;
    private final Set<Pass> passes = ConcurrentHashMap.newKeySet();
    private final Map<Block, Set<UUID>> audiences = new ConcurrentHashMap<>();
    private volatile int startData = -1;

    public ParrotDance(Main plugin) {
        this.plugin = plugin;
    }

    public boolean passes(Player player, int effectId, int x, int y, int z, int data) {
        // A vanilla record-start for the real disc can share the position; only ours carries this data.
        if (effectId == RECORD_START && startData >= 0 && data != startData) return false;
        if (!passes.remove(new Pass(player.getUniqueId(), effectId, x, y, z))) return false;
        if (effectId == RECORD_START) startData = data;
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrackStart(TrackStartEvent event) {
        Block block = event.getJukebox();
        if (block.getType() != Material.JUKEBOX) return;

        BoundingBox reach = BoundingBox.of(block).expand(PARROT_REACH);
        if (block.getWorld().getNearbyEntities(reach, Parrot.class::isInstance).isEmpty()) return;

        Location at = block.getLocation();
        Location center = at.clone().add(0.5, 0.5, 0.5);
        Set<UUID> audience = audiences.computeIfAbsent(block, b -> ConcurrentHashMap.newKeySet());
        for (Player player : block.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) > RANGE * RANGE) continue;
            audience.add(player.getUniqueId());
            Tasks.entity(plugin, player, () -> startDance(player, at));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlaybackStop(PlaybackStopEvent event) {
        Set<UUID> audience = audiences.remove(event.getJukebox());
        if (audience == null) return;

        Location at = event.getJukebox().getLocation();
        for (UUID id : audience) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.getWorld().equals(at.getWorld())) continue;
            Tasks.entity(plugin, player, () -> stopDance(player, at));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        passes.removeIf(pass -> pass.player().equals(id));
        audiences.values().forEach(audience -> audience.remove(id));
    }

    private void startDance(Player player, Location at) {
        if (!player.isOnline()) return;
        Pass pass = pass(player, RECORD_START, at);
        passes.add(pass);
        try {
            player.playEffect(at, Effect.RECORD_PLAY, SILENT_START_DISC);
        } catch (RuntimeException e) {
            passes.remove(pass);
            return;
        }
        Chat.clearActionBar(player);

        long[] elapsed = {0};
        Tasks.Handle[] mute = new Tasks.Handle[1];
        mute[0] = Tasks.entityTimer(plugin, player, () -> {
            player.stopSound(SILENT_START_SOUND, SoundCategory.RECORDS);
            elapsed[0] += MUTE_PERIOD;
            if (elapsed[0] >= MUTE_FOR && mute[0] != null) mute[0].cancel();
        }, 1L, MUTE_PERIOD);
    }

    private void stopDance(Player player, Location at) {
        if (!player.isOnline()) return;
        Pass pass = pass(player, RECORD_STOP, at);
        passes.add(pass);
        if (!WorldEventPacketInterceptor.send(player, RECORD_STOP, at.getBlockX(), at.getBlockY(), at.getBlockZ(), 0)) {
            passes.remove(pass);
        }
    }

    private static Pass pass(Player player, int effectId, Location at) {
        return new Pass(player.getUniqueId(), effectId, at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }
}
