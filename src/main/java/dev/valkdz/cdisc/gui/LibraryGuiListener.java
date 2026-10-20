package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.disc.ItemUtils;
import dev.valkdz.cdisc.feature.library.TrackLibrary;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class LibraryGuiListener implements Listener {

    private final Main plugin;

    public LibraryGuiListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof LibraryGuiHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;
        LibraryGuiManager screens = plugin.getLibraryGuiManager();
        Inventory top = e.getView().getTopInventory();
        int raw = e.getRawSlot();

        if (raw >= top.getSize()) {
            boolean shiftedDisc = e.getClick().isShiftClick() && ItemUtils.isDisc(e.getCurrentItem());
            if (!shiftedDisc && e.getAction() != InventoryAction.COLLECT_TO_CURSOR
                    && e.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                return;
            }
            e.setCancelled(true);
            if (!shiftedDisc) return;
            ItemStack disc = e.getCurrentItem().clone();
            if (screens.writeSelected(player, holder, disc)) e.setCurrentItem(disc);
            return;
        }

        e.setCancelled(true);
        switch (raw) {
            case LibraryGuiManager.SLOT_EXIT -> player.closeInventory();
            case LibraryGuiManager.SLOT_PREV -> screens.turn(player, holder, -1);
            case LibraryGuiManager.SLOT_NEXT -> screens.turn(player, holder, 1);
            case LibraryGuiManager.SLOT_WRITE -> {
                ItemStack cursor = e.getCursor();
                if (cursor == null || cursor.getType().isAir()) return;
                if (!ItemUtils.isDisc(cursor)) {
                    player.sendMessage("§c" + plugin.getMessageManager().get(player, "library.discs_only"));
                    return;
                }
                ItemStack disc = cursor.clone();
                if (screens.writeSelected(player, holder, disc)) e.getView().setCursor(disc);
            }
            default -> {
                TrackLibrary.Track track = holder.trackAt(raw);
                if (track == null) return;
                if (e.getClick().isShiftClick()) {
                    screens.remove(player, holder, track);
                } else {
                    screens.select(player, holder, track);
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof LibraryGuiHolder)) return;
        int size = e.getView().getTopInventory().getSize();
        for (int slot : e.getRawSlots()) {
            if (slot < size) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof LibraryGuiHolder holder && e.getPlayer() instanceof Player player) {
            plugin.getLibraryGuiManager().closed(player, holder);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getTrackLibrary().unload(e.getPlayer().getUniqueId());
    }
}
