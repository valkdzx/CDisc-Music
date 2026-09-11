package dev.valkdz.cdisc.util;

import dev.valkdz.cdisc.Main;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class DiscStorage {

    public static final NamespacedKey STORED_DISCS_KEY =
            new NamespacedKey(Main.getInstance(), "cdisc_stored_discs");

    private DiscStorage() {
    }

    public static void store(ItemStack jukebox, List<ItemStack> discs) {
        ItemMeta meta = jukebox.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (discs.isEmpty()) {
            pdc.remove(STORED_DISCS_KEY);
        } else {
            byte[] encoded = encode(discs);
            if (encoded == null) return;
            pdc.set(STORED_DISCS_KEY, PersistentDataType.BYTE_ARRAY, encoded);
        }
        jukebox.setItemMeta(meta);
    }

    public static List<ItemStack> read(ItemStack jukebox) {
        if (jukebox == null) return new ArrayList<>();
        ItemMeta meta = jukebox.getItemMeta();
        if (meta == null) return new ArrayList<>();

        byte[] encoded = meta.getPersistentDataContainer()
                .get(STORED_DISCS_KEY, PersistentDataType.BYTE_ARRAY);
        if (encoded == null) return new ArrayList<>();

        List<ItemStack> decoded = decode(encoded);
        return decoded == null ? new ArrayList<>() : decoded;
    }

    public static boolean hasStoredDiscs(ItemStack jukebox) {
        if (jukebox == null) return false;
        ItemMeta meta = jukebox.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(STORED_DISCS_KEY, PersistentDataType.BYTE_ARRAY);
    }

    private static byte[] encode(List<ItemStack> discs) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(discs.size());
            for (ItemStack disc : discs) {
                out.writeObject(disc);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (Exception e) {
            Main.getInstance().getLogger().warning(
                    "[CDisc] Could not store discs in the jukebox item: " + e.getMessage());
            return null;
        }
    }

    private static List<ItemStack> decode(byte[] encoded) {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(encoded);
             BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
            int count = in.readInt();
            List<ItemStack> discs = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                Object read = in.readObject();
                if (read instanceof ItemStack disc) discs.add(disc);
            }
            return discs;
        } catch (Exception e) {

            Main.getInstance().getLogger().warning(
                    "[CDisc] Could not read discs out of a jukebox item: " + e.getMessage());
            return null;
        }
    }
}
