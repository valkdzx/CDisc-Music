package dev.valkdz.cdisc.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class PvDiscs {

    private static final String NAMESPACE = "pv-addon-discs";

    private static final String LEGACY_NAMESPACE = "pv-addon-disks";

    private static final NamespacedKey IDENTIFIER =
            new NamespacedKey(NAMESPACE, "identifier");
    private static final NamespacedKey LEGACY_IDENTIFIER =
            new NamespacedKey(LEGACY_NAMESPACE, "identifier");

    private PvDiscs() {
    }

    public static String identifierOf(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String identifier = pdc.get(IDENTIFIER, PersistentDataType.STRING);
        if (identifier == null) {
            identifier = pdc.get(LEGACY_IDENTIFIER, PersistentDataType.STRING);
        }
        return identifier == null || identifier.isBlank() ? null : identifier;
    }

    public static boolean isPvDisc(ItemStack item) {
        return identifierOf(item) != null;
    }

    @SuppressWarnings("deprecation")
    public static String nameOf(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return null;
        String name = meta.getDisplayName();
        return name.isBlank() ? null : name;
    }

    public static void strip(ItemMeta meta) {
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        List<NamespacedKey> ours = new ArrayList<>();
        for (NamespacedKey key : pdc.getKeys()) {
            String namespace = key.getNamespace();
            if (NAMESPACE.equals(namespace) || LEGACY_NAMESPACE.equals(namespace)) {
                ours.add(key);
            }
        }
        for (NamespacedKey key : ours) {
            pdc.remove(key);
        }
    }
}
