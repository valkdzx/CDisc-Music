package dev.valkdz.cdisc.lyrics;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Config;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LyricsLook {

    private static final NamespacedKey STYLE =
            new NamespacedKey(Main.getInstance(), "cdisc_hologram_style");

    private static final Map<Block, Entry> lastKnown = new ConcurrentHashMap<>();

    private record Entry(HologramStyle style) {
    }

    private LyricsLook() {
    }

    public static HologramStyle get(Block block) {
        return read(block).style();
    }

    public static HologramStyle resolve(Block block, HologramStyle defaults) {
        HologramStyle own = get(block);
        return own != null ? own : defaults;
    }

    public static HologramStyle resolve(Block block, Config config) {
        return resolve(block, HologramStyle.fromConfig(config));
    }

    public static boolean isCustomised(Block block) {
        return get(block) != null;
    }

    public static void set(Block block, HologramStyle style) {
        lastKnown.put(block, new Entry(style));

        if (!(block.getState() instanceof TileState state)) return;

        state.getPersistentDataContainer()
                .set(STYLE, PersistentDataType.STRING, style.toJsonString());
        state.update(true, false);
    }

    public static void reset(Block block) {
        lastKnown.put(block, new Entry(null));

        if (!(block.getState() instanceof TileState state)) return;

        state.getPersistentDataContainer().remove(STYLE);
        state.update(true, false);
    }

    public static void forget(Block block) {
        lastKnown.remove(block);
    }

    private static Entry read(Block block) {
        Entry remembered = lastKnown.get(block);
        if (remembered != null) return remembered;

        if (block.getState() instanceof TileState state) {
            String json = state.getPersistentDataContainer()
                    .get(STYLE, PersistentDataType.STRING);

            Entry entry = new Entry(json == null ? null : HologramStyle.fromJsonString(
                    json, HologramStyle.fromConfig(Main.getInstance().cdiscConfig())));
            lastKnown.put(block, entry);
            return entry;
        }

        return new Entry(null);
    }
}
