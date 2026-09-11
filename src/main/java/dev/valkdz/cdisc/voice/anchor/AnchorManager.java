package dev.valkdz.cdisc.voice.anchor;

import dev.valkdz.cdisc.Main;
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
        AnchorType type = plugin.cdiscConfig().getAnchorType();
        Location location = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();

        Entity entity = switch (type) {
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

        return new SoundAnchor(entity, type);
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
