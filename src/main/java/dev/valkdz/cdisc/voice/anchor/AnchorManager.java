package dev.valkdz.cdisc.voice.anchor;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Marker;
import org.bukkit.persistence.PersistentDataType;

public final class AnchorManager {

    private static final String TAG_VALUE = "cdisc-sound-anchor";

    private final Main plugin;
    private final NamespacedKey anchorKey;

    public AnchorManager(Main plugin) {
        this.plugin = plugin;
        this.anchorKey = new NamespacedKey(plugin, "cdisc_sound_anchor");
    }

    public SoundAnchor createFor(Block block) {
        return createAt(block.getLocation().add(0.5, 0.5, 0.5));
    }

    public SoundAnchor createAt(Location location) {
        AnchorType type = plugin.cdiscConfig().getAnchorType();
        return new SoundAnchor(plugin, spawn(type, location), type);
    }

    public void moveTo(SoundAnchor anchor, Location location) {
        anchor.replaceEntity(spawn(anchor.type(), location));
    }

    private Entity spawn(AnchorType type, Location location) {
        World world = location.getWorld();

        return switch (type) {
            case BLOCK_DISPLAY -> world.spawn(location, BlockDisplay.class, display -> {

                display.setBlock(Material.AIR.createBlockData());
                prepare(display);
            });
            case MARKER -> world.spawn(location, Marker.class, this::prepare);
            case ARMOR_STAND -> world.spawn(location, ArmorStand.class, stand -> {
                stand.setVisible(false);
                stand.setMarker(true);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                prepare(stand);
            });
        };
    }

    private void prepare(Entity entity) {

        entity.setPersistent(false);
        entity.setSilent(true);
        entity.getPersistentDataContainer().set(anchorKey, PersistentDataType.STRING, TAG_VALUE);
    }

    public boolean isAnchor(Entity entity) {
        return entity.getPersistentDataContainer().has(anchorKey, PersistentDataType.STRING);
    }

    public int sweepOrphans() {
        // Folia has no world-wide entity view from the global thread, and none of these persist.
        if (Tasks.isFolia()) return 0;

        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!isAnchor(entity)) continue;
                entity.remove();
                removed++;
            }
        }
        return removed;
    }
}
