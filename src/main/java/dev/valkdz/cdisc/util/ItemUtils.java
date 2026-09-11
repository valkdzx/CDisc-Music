package dev.valkdz.cdisc.util;

import dev.valkdz.cdisc.Main;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
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

    public static ItemStack getDiscInHand(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (isDisc(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        return isDisc(off) ? off : null;
    }

    public static void saveTrackToDisc(ItemStack item, String query, String title, String author) {
        saveTrackToDisc(item, query, null, title, author, null);
    }

    public static void saveTrackToDisc(ItemStack item, String query, String fallbackQuery, String title, String author) {
        saveTrackToDisc(item, query, fallbackQuery, title, author, null);
    }

    public static void saveTrackToDisc(ItemStack item, String query, String fallbackQuery, String title,
                                       String author, String musicFetch) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
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

        List<String> lore = new ArrayList<>();
        lore.add("§7Author: §f" + author);
        lore.add("§7Track: §f" + title);
        meta.setLore(lore);

        item.setItemMeta(meta);
    }

    public record DiscData(String query, String fallback, String title, String author, String fetch) {}

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
                pdc.get(MUSIC_FETCH_KEY, PersistentDataType.STRING)
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
        meta.setLore(null);

        item.setItemMeta(meta);
    }
}
