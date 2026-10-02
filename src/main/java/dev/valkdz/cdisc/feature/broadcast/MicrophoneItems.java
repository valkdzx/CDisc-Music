package dev.valkdz.cdisc.feature.broadcast;

import dev.valkdz.cdisc.Main;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

public final class MicrophoneItems {

    public static final NamespacedKey MIC_KEY = new NamespacedKey(Main.getInstance(), "cdisc_mic");
    public static final NamespacedKey HOST_KEY = new NamespacedKey(Main.getInstance(), "cdisc_broadcast_host");

    private MicrophoneItems() {
    }

    public static ItemStack create(Main plugin, Player owner, Microphone mic, String stationName) {
        ItemStack item = new ItemStack(Material.LEVER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(owner, "broadcast.item.name",
                mic.color().chat(), label(plugin, owner, mic)));
        meta.setLore(List.of(
                plugin.getMessageManager().get(owner, "broadcast.item.station", stationName),
                plugin.getMessageManager().get(owner, "broadcast.item.hint")));
        meta.getPersistentDataContainer().set(MIC_KEY, PersistentDataType.STRING, mic.id().toString());
        item.setItemMeta(meta);
        return item;
    }

    public static String label(Main plugin, Player viewer, Microphone mic) {
        return plugin.getMessageManager().get(viewer, mic.color().messageKey());
    }

    public static UUID micIdOf(ItemStack item) {
        if (item == null || item.getType() != Material.LEVER || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        String raw = meta == null ? null : meta.getPersistentDataContainer().get(MIC_KEY, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isMicrophone(ItemStack item) {
        return micIdOf(item) != null;
    }

    public static void setHost(ItemStack disc, UUID host) {
        ItemMeta meta = disc.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(HOST_KEY, PersistentDataType.STRING, host.toString());
        disc.setItemMeta(meta);
    }

    public static UUID hostOf(ItemStack disc) {
        if (disc == null || !disc.hasItemMeta()) return null;
        ItemMeta meta = disc.getItemMeta();
        String raw = meta == null ? null : meta.getPersistentDataContainer().get(HOST_KEY, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
