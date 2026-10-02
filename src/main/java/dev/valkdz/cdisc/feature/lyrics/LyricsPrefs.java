package dev.valkdz.cdisc.feature.lyrics;

import dev.valkdz.cdisc.Main;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LyricsPrefs {

    private static final NamespacedKey MODE_KEY =
            new NamespacedKey(Main.getInstance(), "cdisc_lyrics_mode");

    private static final Map<UUID, LyricsMode> lastKnown = new ConcurrentHashMap<>();

    private LyricsPrefs() {
    }

    public static LyricsMode mode(Player player) {
        if (!Main.getInstance().cdiscConfig().isLyricsEnabled()) return LyricsMode.OFF;

        // Asked from jukebox region threads for every reader in range, so the player's own
        // container is read once, on first sight, and the answer kept until it changes.
        return lastKnown.computeIfAbsent(player.getUniqueId(), id -> {
            Byte stored = player.getPersistentDataContainer().get(MODE_KEY, PersistentDataType.BYTE);
            return stored == null ? defaultMode() : LyricsMode.ofCode(stored);
        });
    }

    public static void setMode(Player player, LyricsMode mode) {
        lastKnown.put(player.getUniqueId(), mode);
        player.getPersistentDataContainer().set(MODE_KEY, PersistentDataType.BYTE, mode.code());
    }

    public static void forget(UUID player) {
        lastKnown.remove(player);
    }

    public static void forgetAll() {
        lastKnown.clear();
    }

    private static LyricsMode defaultMode() {
        return Main.getInstance().cdiscConfig().isLyricsDefaultOn() ? LyricsMode.LYRICS : LyricsMode.OFF;
    }
}
