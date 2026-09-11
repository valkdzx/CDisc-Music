package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class PlaylistGuiListener implements Listener {

    private final Main plugin;

    public PlaylistGuiListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof PlaylistGuiHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;

        Inventory top = e.getView().getTopInventory();
        int raw = e.getRawSlot();
        boolean topClicked = raw < top.getSize();

        if (!topClicked) {

            if (!e.getClick().isShiftClick()) return;
            e.setCancelled(true);
            shiftIn(player, holder, top, e.getCurrentItem());
            return;
        }

        if (raw >= PlaylistGuiManager.SLOTS_PER_PAGE) {
            e.setCancelled(true);
            handleControl(player, holder, top, raw);
            return;
        }

        if (holder.trackIndexAt(raw) < 0) {
            e.setCancelled(true);
            return;
        }

        ItemStack cursor = e.getCursor();
        if (cursor != null && !cursor.getType().isAir()
                && !PlaylistGuiManager.isBlankDisc(cursor)) {
            e.setCancelled(true);
            player.sendMessage("§c" + plugin.getMessageManager().get(player, "playlist.blank_only"));
            return;
        }

        Bukkit.getScheduler().runTask(plugin,
                () -> plugin.getPlaylistGuiManager().harvest(holder, top));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof PlaylistGuiHolder holder)) return;

        Inventory top = e.getView().getTopInventory();
        for (int slot : e.getRawSlots()) {
            if (slot >= top.getSize()) continue;
            if (slot >= PlaylistGuiManager.SLOTS_PER_PAGE || holder.trackIndexAt(slot) < 0) {
                e.setCancelled(true);
                return;
            }
        }
        if (!PlaylistGuiManager.isBlankDisc(e.getOldCursor())) {
            e.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin,
                () -> plugin.getPlaylistGuiManager().harvest(holder, top));
    }

    private void shiftIn(Player player, PlaylistGuiHolder holder, Inventory top, ItemStack clicked) {
        if (!PlaylistGuiManager.isBlankDisc(clicked)) return;

        plugin.getPlaylistGuiManager().harvest(holder, top);

        int moved = 0;
        int amount = clicked.getAmount();
        while (amount > 0) {
            int index = holder.firstEmpty();
            if (index < 0) break;

            ItemStack single = clicked.clone();
            single.setAmount(1);
            holder.place(index, single);
            amount--;
            moved++;
        }

        if (moved == 0) {
            player.sendMessage("§e" + plugin.getMessageManager().get(player, "playlist.all_covered"));
            return;
        }
        clicked.setAmount(amount);
        plugin.getPlaylistGuiManager().render(player, holder, top);
    }

    private void handleControl(Player player, PlaylistGuiHolder holder, Inventory top, int raw) {
        switch (raw) {
            case PlaylistGuiManager.SLOT_EXIT -> player.closeInventory();
            case PlaylistGuiManager.SLOT_PREV -> {
                if (holder.getPage() <= 0) return;
                plugin.getPlaylistGuiManager().harvest(holder, top);
                holder.setPage(holder.getPage() - 1);
                plugin.getPlaylistGuiManager().render(player, holder, top);
            }
            case PlaylistGuiManager.SLOT_NEXT -> {
                if (holder.getPage() >= holder.lastPage()) return;
                plugin.getPlaylistGuiManager().harvest(holder, top);
                holder.setPage(holder.getPage() + 1);
                plugin.getPlaylistGuiManager().render(player, holder, top);
            }
            default -> {

            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof PlaylistGuiHolder holder)) return;
        if (!(e.getPlayer() instanceof Player player)) return;

        plugin.getPlaylistGuiManager().harvest(holder, e.getInventory());
        plugin.getPlaylistGuiManager().writeAndReturn(player, holder);
    }
}
