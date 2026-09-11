package dev.valkdz.cdisc.util;

import dev.valkdz.cdisc.Main;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class PlayerPrefs {

    private static final NamespacedKey TRACK_MESSAGES =
            new NamespacedKey(Main.getInstance(), "cdisc_track_messages");

    private static final NamespacedKey LOCAL_VOLUME =
            new NamespacedKey(Main.getInstance(), "cdisc_local_volume");

    private static final NamespacedKey LYRICS_SIDEBAR =
            new NamespacedKey(Main.getInstance(), "cdisc_lyrics_sidebar");

    public static final int VOLUME_FOLLOWS_JUKEBOX = -1;

    private PlayerPrefs() {
    }

    public static boolean showsTrackMessages(Player player) {
        Byte stored = container(player).get(TRACK_MESSAGES, PersistentDataType.BYTE);
        return stored == null || stored != 0;
    }

    public static void setTrackMessages(Player player, boolean show) {
        PersistentDataContainer pdc = container(player);
        if (show) {

            pdc.remove(TRACK_MESSAGES);
        } else {
            pdc.set(TRACK_MESSAGES, PersistentDataType.BYTE, (byte) 0);
        }
    }

    public static int localVolume(Player player) {
        Integer stored = container(player).get(LOCAL_VOLUME, PersistentDataType.INTEGER);
        return stored == null ? VOLUME_FOLLOWS_JUKEBOX
                : dev.valkdz.cdisc.speaker.SpeakerSettings.clampVolume(stored);
    }

    public static void setLocalVolume(Player player, int volume) {
        PersistentDataContainer pdc = container(player);
        if (volume < 0) {
            pdc.remove(LOCAL_VOLUME);
            return;
        }
        pdc.set(LOCAL_VOLUME, PersistentDataType.INTEGER,
                dev.valkdz.cdisc.speaker.SpeakerSettings.clampVolume(volume));
    }

    public static int effectiveLocalVolume(Player player, int jukeboxVolume) {
        int own = localVolume(player);
        return own == VOLUME_FOLLOWS_JUKEBOX ? jukeboxVolume : own;
    }

    public static boolean showsLyricsScoreboard(Player player) {
        Byte stored = container(player).get(LYRICS_SIDEBAR, PersistentDataType.BYTE);
        return stored != null && stored != 0;
    }

    public static void setLyricsScoreboard(Player player, boolean show) {
        PersistentDataContainer pdc = container(player);
        if (show) {
            pdc.set(LYRICS_SIDEBAR, PersistentDataType.BYTE, (byte) 1);
        } else {

            pdc.remove(LYRICS_SIDEBAR);
        }
    }

    public static boolean toggleLyricsScoreboard(Player player) {
        boolean next = !showsLyricsScoreboard(player);
        setLyricsScoreboard(player, next);
        return next;
    }

    public static boolean toggleTrackMessages(Player player) {
        boolean next = !showsTrackMessages(player);
        setTrackMessages(player, next);
        return next;
    }

    private static PersistentDataContainer container(Player player) {
        return player.getPersistentDataContainer();
    }
}
