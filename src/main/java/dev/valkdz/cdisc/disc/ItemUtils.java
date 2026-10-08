package dev.valkdz.cdisc.disc;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.config.MessageManager;
import dev.valkdz.cdisc.util.TimeUtils;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class ItemUtils {
    public static final NamespacedKey URL_KEY = new NamespacedKey(Main.getInstance(), "cdisc_url");
    public static final NamespacedKey FALLBACK_URL_KEY = new NamespacedKey(Main.getInstance(), "cdisc_fallback_url");
    public static final NamespacedKey TITLE_KEY = new NamespacedKey(Main.getInstance(), "cdisc_title");
    public static final NamespacedKey AUTHOR_KEY = new NamespacedKey(Main.getInstance(), "cdisc_author");
    public static final NamespacedKey MUSIC_FETCH_KEY = new NamespacedKey(Main.getInstance(), "music_fetch");
    public static final NamespacedKey CONTENT_TYPE_KEY = new NamespacedKey(Main.getInstance(), "cdisc_content_type");
    public static final NamespacedKey LENGTH_KEY = new NamespacedKey(Main.getInstance(), "cdisc_length_ms");
    public static final NamespacedKey LIVE_KEY = new NamespacedKey(Main.getInstance(), "cdisc_live");
    public static final NamespacedKey GEO_KEY = new NamespacedKey(Main.getInstance(), "cdisc_geo");
    public static final NamespacedKey HINT_AT_KEY = new NamespacedKey(Main.getInstance(), "cdisc_hint_at");
    public static final NamespacedKey PLAN_KEY = new NamespacedKey(Main.getInstance(), "cdisc_plan");
    public static final NamespacedKey LOCKED_KEY = new NamespacedKey(Main.getInstance(), "cdisc_locked");

    public static boolean isDisc(ItemStack item) {
        return item != null && item.getType().toString().startsWith("MUSIC_DISC_");
    }

    public static boolean isCdiscDisc(ItemStack item) {
        return isDisc(item) && hasCdiscData(item.getItemMeta());
    }

    public static boolean isCdiscDisc(ItemStack item, ItemMeta meta) {
        return isDisc(item) && hasCdiscData(meta);
    }

    private static boolean hasCdiscData(ItemMeta meta) {
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            if ("cdisc".equals(key.getNamespace())) return true;
        }
        return false;
    }

    // A locked disc exists only inside a jukebox queue: it is never dropped, handed out or
    // hopper-pulled, so a plugin that queues one creates no item.
    public static boolean isLocked(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(LOCKED_KEY, PersistentDataType.BYTE);
    }

    public static void lock(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(LOCKED_KEY, PersistentDataType.BYTE, (byte) 1);
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(Main.getInstance().getMessageManager().get(null, "disc.lore.locked"));
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    public static ItemStack getDiscInHand(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (isDisc(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        return isDisc(off) ? off : null;
    }

    public static void saveTrackToDisc(Player player, ItemStack item, String query, String title, String author) {
        saveTrackToDisc(player, item, query, null, title, author, null, null);
    }

    public static void saveTrackToDisc(Player player, ItemStack item, String query, String fallbackQuery,
                                       String title, String author, String musicFetch) {
        saveTrackToDisc(player, item, query, fallbackQuery, title, author, musicFetch, null);
    }

    public static void saveTrackToDisc(Player player, ItemStack item, String query, String fallbackQuery,
                                       String title, String author, String musicFetch, Hint hint) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        writeHint(pdc, hint);
        pdc.set(URL_KEY, PersistentDataType.STRING, query);
        if (fallbackQuery != null && !fallbackQuery.equals(query)) {
            pdc.set(FALLBACK_URL_KEY, PersistentDataType.STRING, fallbackQuery);
        } else {
            pdc.remove(FALLBACK_URL_KEY);
        }
        pdc.set(TITLE_KEY, PersistentDataType.STRING, title);
        pdc.set(AUTHOR_KEY, PersistentDataType.STRING, author);
        if (musicFetch != null) {
            pdc.set(MUSIC_FETCH_KEY, PersistentDataType.STRING, musicFetch);
        } else {
            pdc.remove(MUSIC_FETCH_KEY);
        }

        meta.setLore(discLore(player, title, author, isDisc(item) ? hint : null));
        meta.addItemFlags(ItemFlag.HIDE_POTION_EFFECTS);
        if (isDisc(item)) songLine(meta, item, false);
        item.setItemMeta(meta);
    }

    private static volatile boolean songLineReported;

    // 1.21 to 1.21.4 print the song from the jukebox component, which the flag above does not reach.
    // getJukeboxPlayable() hands back song 13 for an unpatched disc, so the disc's own song is set back.
    private static void songLine(ItemMeta meta, ItemStack item, boolean show) {
        try {
            Class<?> type = Class.forName("org.bukkit.inventory.meta.components.JukeboxPlayableComponent");
            Object song = ItemMeta.class.getMethod("getJukeboxPlayable").invoke(meta);
            String key = item.getType().name().substring("MUSIC_DISC_".length()).toLowerCase(java.util.Locale.ROOT);
            type.getMethod("setSongKey", NamespacedKey.class).invoke(song, NamespacedKey.minecraft(key));
            type.getMethod("setShowInTooltip", boolean.class).invoke(song, show);
            ItemMeta.class.getMethod("setJukeboxPlayable", type).invoke(meta, song);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException | RuntimeException e) {
            Main plugin = Main.getInstance();
            if (songLineReported || !plugin.cdiscConfig().isDebug()) return;
            songLineReported = true;
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : e;
            plugin.getLogger().warning("Could not change the vanilla song line on a disc: " + cause);
        }
    }

    private static List<String> discLore(Player player, String title, String author, Hint hint) {
        MessageManager messages = Main.getInstance().getMessageManager();
        List<String> lore = new ArrayList<>();
        if (author == null || author.isBlank()) {
            lore.add(messages.get(player, "disc.lore.title_only", title));
        } else {
            lore.add(messages.get(player, "disc.lore.author", author));
            lore.add(messages.get(player, "disc.lore.track", title));
        }
        if (hint != null && hint.live()) {
            lore.add(messages.get(player, "disc.lore.live"));
        } else if (hint != null && hint.lengthMs() > 0) {
            lore.add(messages.get(player, "disc.lore.length", TimeUtils.format(hint.lengthMs())));
        }
        return lore;
    }

    private static final long HINT_TTL_MS = 14L * 24 * 60 * 60 * 1000;

    public record Hint(String contentType, long lengthMs, boolean live, Boolean allowedHere,
                       long writtenAt, String plan) {

        public boolean fresh() {
            return writtenAt > 0 && System.currentTimeMillis() - writtenAt < HINT_TTL_MS;
        }
    }

    public record DiscData(String query, String fallback, String title, String author, String fetch,
                           Hint hint) {}

    private static void writeHint(PersistentDataContainer pdc, Hint hint) {
        if (hint == null) {
            pdc.remove(CONTENT_TYPE_KEY);
            pdc.remove(LENGTH_KEY);
            pdc.remove(LIVE_KEY);
            pdc.remove(GEO_KEY);
            pdc.remove(HINT_AT_KEY);
            pdc.remove(PLAN_KEY);
            return;
        }

        if (hint.contentType() != null) {
            pdc.set(CONTENT_TYPE_KEY, PersistentDataType.STRING, hint.contentType());
        }
        if (hint.lengthMs() > 0) {
            pdc.set(LENGTH_KEY, PersistentDataType.LONG, hint.lengthMs());
        }
        pdc.set(LIVE_KEY, PersistentDataType.BYTE, (byte) (hint.live() ? 1 : 0));
        if (hint.allowedHere() != null) {
            pdc.set(GEO_KEY, PersistentDataType.BYTE, (byte) (hint.allowedHere() ? 1 : 0));
        }
        if (hint.plan() != null) {
            pdc.set(PLAN_KEY, PersistentDataType.STRING, hint.plan());
        } else {
            pdc.remove(PLAN_KEY);
        }
        pdc.set(HINT_AT_KEY, PersistentDataType.LONG, System.currentTimeMillis());
    }

    private static Hint readHint(PersistentDataContainer pdc) {
        Long writtenAt = pdc.get(HINT_AT_KEY, PersistentDataType.LONG);
        if (writtenAt == null) return null;

        Byte geo = pdc.get(GEO_KEY, PersistentDataType.BYTE);
        Byte live = pdc.get(LIVE_KEY, PersistentDataType.BYTE);
        Long length = pdc.get(LENGTH_KEY, PersistentDataType.LONG);

        return new Hint(
                pdc.get(CONTENT_TYPE_KEY, PersistentDataType.STRING),
                length == null ? 0 : length,
                live != null && live != 0,
                geo == null ? null : geo != 0,
                writtenAt,
                pdc.get(PLAN_KEY, PersistentDataType.STRING));
    }

    public static DiscData readDiscData(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String query = pdc.get(URL_KEY, PersistentDataType.STRING);
        if (query == null) return null;
        return new DiscData(
                query,
                pdc.get(FALLBACK_URL_KEY, PersistentDataType.STRING),
                pdc.get(TITLE_KEY, PersistentDataType.STRING),
                pdc.get(AUTHOR_KEY, PersistentDataType.STRING),
                pdc.get(MUSIC_FETCH_KEY, PersistentDataType.STRING),
                readHint(pdc)
        );
    }

    public static String getMusicFetch(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(MUSIC_FETCH_KEY, PersistentDataType.STRING);
    }

    public static void clearDisc(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.getPersistentDataContainer().remove(URL_KEY);
        meta.getPersistentDataContainer().remove(FALLBACK_URL_KEY);
        meta.getPersistentDataContainer().remove(TITLE_KEY);
        meta.getPersistentDataContainer().remove(AUTHOR_KEY);
        meta.getPersistentDataContainer().remove(MUSIC_FETCH_KEY);
        writeHint(meta.getPersistentDataContainer(), null);
        meta.setLore(null);
        meta.removeItemFlags(ItemFlag.HIDE_POTION_EFFECTS);
        if (isDisc(item)) songLine(meta, item, true);

        item.setItemMeta(meta);
    }
}
