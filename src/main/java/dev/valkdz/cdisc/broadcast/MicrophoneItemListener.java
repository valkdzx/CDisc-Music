package dev.valkdz.cdisc.broadcast;

import dev.valkdz.cdisc.Main;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class MicrophoneItemListener implements Listener {

    private final Main plugin;

    public MicrophoneItemListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        UUID micId = MicrophoneItems.micIdOf(event.getItem());
        if (micId == null || plugin.getBroadcastManager().isSelecting(event.getPlayer())) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        BroadcastManager.Found found = plugin.getBroadcastManager().find(micId);
        if (found == null || !player.getUniqueId().equals(found.mic().holder())) {
            plugin.getBroadcastManager().validateLevers(player);
            return;
        }
        plugin.getBroadcastGui().openLever(player, found.station(), found.mic());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (MicrophoneItems.isMicrophone(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (MicrophoneItems.isMicrophone(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (MicrophoneItems.isMicrophone(event.getMainHandItem())
                || MicrophoneItems.isMicrophone(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityUse(PlayerInteractEntityEvent event) {
        if (MicrophoneItems.isMicrophone(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (MicrophoneItems.isMicrophone(event.getPlayerItem())) event.setCancelled(true);
    }

    // Moving it about the player's own inventory is fine; anything that could leave it in
    // another inventory, a bundle or the crafting grid is refused.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ItemStack hotbar = event.getHotbarButton() >= 0 && event.getWhoClicked() instanceof Player player
                ? player.getInventory().getItem(event.getHotbarButton()) : null;
        boolean involved = MicrophoneItems.isMicrophone(current) || MicrophoneItems.isMicrophone(cursor)
                || MicrophoneItems.isMicrophone(hotbar);
        if (!involved) return;

        boolean ownInventory = event.getView().getTopInventory().getType() == InventoryType.CRAFTING
                && event.getSlotType() != InventoryType.SlotType.CRAFTING
                && event.getSlotType() != InventoryType.SlotType.RESULT;
        boolean intoTop = event.getClickedInventory() == event.getView().getTopInventory();
        boolean bundle = isBundle(current) || isBundle(cursor);

        if (bundle || (!ownInventory && (intoTop || event.isShiftClick()
                || event.getClick().isKeyboardClick() || event.getAction().name().contains("COLLECT")))) {
            event.setCancelled(true);
        }
        if (event.getSlotType() == InventoryType.SlotType.OUTSIDE) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (!MicrophoneItems.isMicrophone(event.getOldCursor())) return;
        int topSize = event.getView().getTopInventory().getSize();
        boolean ownInventory = event.getView().getTopInventory().getType() == InventoryType.CRAFTING;
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && !ownInventory || ownInventory && raw < 5) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;
        if (event.getDrops().removeIf(MicrophoneItems::isMicrophone)) {
            plugin.getBroadcastManager().holderGone(event.getEntity());
        }
    }

    private static boolean isBundle(ItemStack item) {
        return item != null && item.getType().name().endsWith("BUNDLE") && item.getType() != Material.AIR;
    }
}
