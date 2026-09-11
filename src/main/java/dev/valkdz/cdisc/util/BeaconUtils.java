package dev.valkdz.cdisc.util;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

public final class BeaconUtils {

    public static final int MAX_RANGE_LEVEL = 3;

    private BeaconUtils() {
    }

    public static int beaconTierBelow(Block jukebox) {
        if (jukebox == null) return 0;

        Block below = jukebox.getRelative(BlockFace.DOWN);
        if (below.getType() == Material.BEACON && below.getState() instanceof Beacon beacon) {
            return beacon.getTier();
        }
        return pyramidTierBelow(jukebox);
    }

    private static int pyramidTierBelow(Block apex) {
        World world = apex.getWorld();
        int bx = apex.getX();
        int by = apex.getY();
        int bz = apex.getZ();

        int tier = 0;
        for (int layer = 1; layer <= 4; layer++) {
            int y = by - layer;
            if (y < world.getMinHeight()) break;

            boolean complete = true;
            for (int x = bx - layer; x <= bx + layer && complete; x++) {
                for (int z = bz - layer; z <= bz + layer; z++) {
                    if (!Tag.BEACON_BASE_BLOCKS.isTagged(world.getBlockAt(x, y, z).getType())) {
                        complete = false;
                        break;
                    }
                }
            }
            if (!complete) break;
            tier = layer;
        }
        return tier;
    }

    public static int maxRangeLevel(int beaconTier) {
        return Math.min(Math.max(beaconTier, 0), MAX_RANGE_LEVEL);
    }
}
