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

        Boolean remembered = lastKnown.get(block);
        if (remembered != null) return remembered;

        // Reading the jukebox costs a full block-entity snapshot, and this is asked on the
        // main thread every few ticks per jukebox, so the answer is kept until it changes.
        Boolean stored = readStored(block);
        if (stored == null) return Main.getInstance().cdiscConfig().isLyricsDefaultOn();

        lastKnown.put(block, stored);
        return stored;
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

    public static void forgetAll() {
        lastKnown.clear();
    }

    public static boolean toggle(Block block) {
        boolean next = !isEnabled(block);
        setEnabled(block, next);
        return next;
    }

    private static Boolean readStored(Block block) {
        if (!(block.getState() instanceof TileState state)) return null;

        Byte value = state.getPersistentDataContainer().get(ENABLED_KEY, PersistentDataType.BYTE);
        return value == null ? Main.getInstance().cdiscConfig().isLyricsDefaultOn() : value != 0;
    }
}
