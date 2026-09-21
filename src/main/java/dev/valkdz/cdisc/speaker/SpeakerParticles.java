package dev.valkdz.cdisc.speaker;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class SpeakerParticles {

    private final Main plugin;
    private Tasks.Handle task;

    public SpeakerParticles(Main plugin) {
        this.plugin = plugin;
    }

    public void start() {

        long period = Math.max(20L, plugin.cdiscConfig().getPortableParticleTicks());
        task = Tasks.globalTimer(plugin, this::emit, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void emit() {
        for (SpeakerGroup group : plugin.getSpeakerGroupManager().all()) {
            Tasks.inRegion(plugin, group.main(), () -> emitGroup(group));
        }
    }

    private void emitGroup(SpeakerGroup group) {
        Block main = blockIfLoaded(group.main());

        if (main == null || !plugin.getAudioPlayerManager().hasActiveSession(main)) return;

        emitAt(main);
        for (Location speaker : group.speakers()) {
            Tasks.inRegion(plugin, speaker, () -> {
                Block block = blockIfLoaded(speaker);
                if (block != null) emitAt(block);
            });
        }
    }

    private void emitAt(Block block) {
        if (block.getType() != Material.JUKEBOX) return;
        if (!SpeakerSettings.of(block).particles()) return;

        World world = block.getWorld();
        world.spawnParticle(Particle.NOTE,
                block.getLocation().add(0.5, 1.2, 0.5),
                1, 0.2, 0.0, 0.2, 1.0);
    }

    private static Block blockIfLoaded(Location location) {
        World world = location.getWorld();
        if (world == null) return null;
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return null;
        return location.getBlock();
    }
}
