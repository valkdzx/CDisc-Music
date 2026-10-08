package dev.valkdz.cdisc.jukebox;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.disc.ItemUtils;
import dev.valkdz.cdisc.jukebox.queue.DiscQueue;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;

public final class LockedDiscGuard implements Listener {

    private final Main plugin;

    public LockedDiscGuard(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent e) {
        if (ItemUtils.isLocked(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (ItemUtils.isLocked(e.getItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!ItemUtils.isLocked(e.getItem().getItemStack())) return;
        e.setCancelled(true);
        e.getItem().remove();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (ItemUtils.isLocked(e.getCursor())) {
            e.setCancelled(true);
            e.getWhoClicked().setItemOnCursor(null);
        }
        if (e.getClickedInventory() instanceof PlayerInventory && ItemUtils.isLocked(e.getCurrentItem())) {
            e.setCancelled(true);
            e.setCurrentItem(null);
        }
        if (e.getClick() == ClickType.NUMBER_KEY) {
            PlayerInventory own = e.getWhoClicked().getInventory();
            if (ItemUtils.isLocked(own.getItem(e.getHotbarButton()))) {
                e.setCancelled(true);
                own.setItem(e.getHotbarButton(), null);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!ItemUtils.isLocked(e.getOldCursor())) return;
        e.setCancelled(true);
        e.getWhoClicked().setItemOnCursor(null);
    }

    // Vanilla pops the record out on this click; a locked one is cleared in place instead and
    // keeps its queue slot, so the click stops the music and nothing lands in the world.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEject(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;
        if (!(block.getState() instanceof Jukebox jukebox) || !ItemUtils.isLocked(jukebox.getRecord())) return;

        Player player = e.getPlayer();
        boolean handsFull = !player.getInventory().getItemInMainHand().getType().isAir()
                || !player.getInventory().getItemInOffHand().getType().isAir();
        if (player.isSneaking() && handsFull) return;

        e.setUseInteractedBlock(Event.Result.DENY);
        if (e.getHand() != EquipmentSlot.HAND) return;

        Tasks.region(plugin, block, () -> {
            PlaybackManager apm = plugin.getAudioPlayerManager();
            DiscQueue queue = apm.getQueue(block);
            if (queue != null) queue.setCurrentIndex(-1);
            plugin.getJukeboxListener().clearPhysicalRecord(block);
            if (apm.hasActiveSession(block)) apm.stopPlaying(block, apm.getGeneration(block));
            plugin.getQueueGuiManager().refreshOpen(block);
        });
    }
}
