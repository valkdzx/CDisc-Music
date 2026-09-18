package dev.valkdz.cdisc.horn;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.ItemUtils;
import dev.valkdz.cdisc.util.TimeUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class GoatHorns {

    public static final NamespacedKey CLIP_KEY = new NamespacedKey(Main.getInstance(), "cdisc_horn_clip_ms");

    private GoatHorns() {
    }

    public static boolean isHorn(ItemStack item) {
        return item != null && item.getType() == Material.GOAT_HORN;
    }

    public static boolean isRecorded(ItemStack item) {
        return isHorn(item) && ItemUtils.readDiscData(item) != null;
    }

    public static ItemStack inHand(Main plugin, Player player) {
        if (!plugin.cdiscConfig().isGoatHornEnabled()) return null;
        ItemStack main = player.getInventory().getItemInMainHand();
        if (isHorn(main)) return main;
        ItemStack off = player.getInventory().getItemInOffHand();
        return isHorn(off) ? off : null;
    }

    public static long maxClipMs(Main plugin) {
        return plugin.cdiscConfig().getGoatHornMaxSeconds() * 1000L;
    }

    private static boolean known(long lengthMs) {
        return lengthMs > 0 && lengthMs != Long.MAX_VALUE;
    }

    public static String refusal(Main plugin, Player player, AudioTrackInfo info) {
        if (info.isStream) return plugin.getMessageManager().get(player, "horn.live_blocked");

        long max = maxClipMs(plugin);
        if (!plugin.cdiscConfig().isGoatHornTrimming() && known(info.length) && info.length > max) {
            return plugin.getMessageManager().get(player, "horn.too_long",
                    TimeUtils.format(info.length), TimeUtils.format(max));
        }
        return null;
    }

    public static long record(Main plugin, ItemStack item, long lengthMs) {
        long max = maxClipMs(plugin);
        long clip = known(lengthMs) ? Math.min(lengthMs, max) : max;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return clip;
        meta.getPersistentDataContainer().set(CLIP_KEY, PersistentDataType.LONG, clip);

        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        lore.add("§7Length: §f" + TimeUtils.format(clip));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return clip;
    }

    public static boolean trimmed(long lengthMs, long clipMs) {
        return known(lengthMs) && clipMs < lengthMs;
    }

    public static long clipOf(Main plugin, ItemStack item) {
        long max = maxClipMs(plugin);
        ItemMeta meta = item.getItemMeta();
        Long stored = meta == null ? null
                : meta.getPersistentDataContainer().get(CLIP_KEY, PersistentDataType.LONG);
        return stored == null || stored <= 0 ? max : Math.min(stored, max);
    }

    public static void clear(ItemStack item) {
        ItemUtils.clearDisc(item);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(CLIP_KEY);
        item.setItemMeta(meta);
    }
}
