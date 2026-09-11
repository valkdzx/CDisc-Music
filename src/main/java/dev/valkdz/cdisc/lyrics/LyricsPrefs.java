package dev.valkdz.cdisc.lyrics;

import dev.valkdz.cdisc.Main;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LyricsPrefs {

    private static final NamespacedKey ENABLED_KEY =
            new NamespacedKey(Main.getInstance(), "cdisc_lyrics_enabled");

    private static final Map<Block, Boolean> lastKnown = new ConcurrentHashMap<>();

    private LyricsPrefs() {
    }

    public static boolean isEnabled(Block block) {
        if (!Main.getInstance().cdiscConfig().isLyricsEnabled()) return false;

        Byte stored = read(block);
        if (stored != null) {
            boolean on = stored != 0;
            lastKnown.put(block, on);
            return on;
        }

        if (!(block.getState() instanceof TileState)) {
            Boolean remembered = lastKnown.get(block);
            if (remembered != null) return remembered;
        }
        return Main.getInstance().cdiscConfig().isLyricsDefaultOn();
    }

    public static void setEnabled(Block block, boolean enabled) {
        lastKnown.put(block, enabled);

        if (!(block.getState() instanceof TileState state)) return;

        PersistentDataContainer pdc = state.getPersistentDataContainer();
        pdc.set(ENABLED_KEY, PersistentDataType.BYTE, (byte) (enabled ? 1 : 0));
        state.update(true, false);
    }

    public static void forget(Block block) {
        lastKnown.remove(block);
    }

    public static boolean toggle(Block block) {
        boolean next = !isEnabled(block);
        setEnabled(block, next);
        return next;
    }

    private static Byte read(Block block) {
        if (!(block.getState() instanceof TileState state)) return null;
        return state.getPersistentDataContainer().get(ENABLED_KEY, PersistentDataType.BYTE);
    }
}
