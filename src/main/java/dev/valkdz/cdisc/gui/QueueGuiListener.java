package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.audio.queue.DiscQueue;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.util.ItemUtils;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class QueueGuiListener implements Listener {

    private final Main plugin;

    public QueueGuiListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof ConfirmGuiHolder confirm) {
            handleConfirm(e, confirm);
            return;
        }
        if (!(e.getInventory().getHolder() instanceof QueueGuiHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        Block block = holder.getBlock();

        boolean alive = apm.hasActiveSession(block) || apm.getQueue(block) != null;
        if (!alive || apm.getGeneration(block) != holder.getGeneration()) {
            e.setCancelled(true);
            player.closeInventory();
            return;
        }
        boolean playing = apm.hasActiveSession(block);

        int raw = e.getRawSlot();
        boolean topClicked = raw < e.getView().getTopInventory().getSize();

        if (topClicked) {
            if (QueueGuiManager.isFillerSlot(raw)) {
                e.setCancelled(true);
                return;
            }
            if (QueueGuiManager.isControlSlot(raw)) {
                e.setCancelled(true);
                handleControl(raw, player, block);
                return;
            }

            DiscQueue queue = apm.getQueue(block);
            int qIndex = QueueGuiManager.queueIndexOf(raw);
            if (playing && queue != null && qIndex == queue.getCurrentIndex()) {
                e.setCancelled(true);
                return;
            }

            ItemStack cursor = e.getCursor();
            ItemStack cell = e.getCurrentItem();

            if (ItemUtils.isCdiscDisc(cursor)) {
                if (isPlaceAction(e.getAction()) && may(player, Action.QUEUE_ADD)) {
                    plugin.getQueueGuiManager().schedulePersist(block, e.getView().getTopInventory());
                } else {
                    e.setCancelled(true);
                }
                return;
            }

            if (ItemUtils.isCdiscDisc(cell)) {
                if (e.getClick() == ClickType.LEFT) {

                    e.setCancelled(true);
                    if (qIndex >= 0) plugin.getQueueGuiManager().openPlayConfirm(player, block, qIndex);
                    return;
                }
                if ((e.getClick() == ClickType.RIGHT || e.getClick().isShiftClick())
                        && may(player, Action.QUEUE_REMOVE)) {

                    plugin.getQueueGuiManager().schedulePersist(block, e.getView().getTopInventory());
                    return;
                }
            }

            e.setCancelled(true);
            return;
        }

        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        if (e.getClick().isShiftClick()) {
            e.setCancelled(true);
            if (!may(player, Action.QUEUE_ADD)) return;
            shiftDiscIntoQueue(e, player);
            plugin.getQueueGuiManager().schedulePersist(block, e.getView().getTopInventory());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ConfirmGuiHolder) {
            e.setCancelled(true);
            return;
        }
        if (!(e.getInventory().getHolder() instanceof QueueGuiHolder holder)) return;

        Block block = holder.getBlock();
        DiscQueue queue = plugin.getAudioPlayerManager().getQueue(block);
        int topSize = e.getView().getTopInventory().getSize();
        boolean touchesTop = false;
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                touchesTop = true;
                if (!QueueGuiManager.isQueueSlot(raw)) {
                    e.setCancelled(true);
                    return;
                }
                if (queue != null && QueueGuiManager.queueIndexOf(raw) == queue.getCurrentIndex()) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
        if (touchesTop) {

            if (!ItemUtils.isCdiscDisc(e.getOldCursor())
                    || !(e.getWhoClicked() instanceof Player dragger)
                    || !may(dragger, Action.QUEUE_ADD)) {
                e.setCancelled(true);
                return;
            }
            plugin.getQueueGuiManager().schedulePersist(block, e.getView().getTopInventory());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        if (e.getInventory().getHolder() instanceof QueueGuiHolder holder) {
            plugin.getQueueGuiManager().onClosed(player, holder.getBlock(), e.getInventory());
        } else if (e.getInventory().getHolder() instanceof ConfirmGuiHolder confirm) {
            plugin.getQueueGuiManager().onConfirmClosed(player, confirm.getBlock());
        }
    }

    private boolean may(Player player, Action action) {
        return plugin.getPermissions().allows(player, action);
    }

    private void handleControl(int rawSlot, Player player, Block block) {
        switch (rawSlot) {
            case QueueGuiManager.SLOT_EXIT -> {

                plugin.getPlayerGuiManager().open(player, block);
            }
            case QueueGuiManager.SLOT_POLICY -> {
                if (!may(player, Action.QUEUE_POLICY)) return;

                DiscQueue queue = plugin.getAudioPlayerManager().getOrCreateQueue(block);
                queue.cyclePolicy();
                plugin.getQueueGuiManager().refreshControls(player, block);
            }
            case QueueGuiManager.SLOT_PAIR -> {
                if (!plugin.cdiscConfig().isSpeakerGroupEnabled()) return;
                if (plugin.getSpeakerGroupManager().groupAt(block) != null) {
                    plugin.getPairGuiManager().openManage(player, block);
                } else {
                    plugin.getPairGuiManager().promptForName(player, block);
                }
            }
            default -> {

            }
        }
    }

    private void shiftDiscIntoQueue(InventoryClickEvent e, Player player) {
        ItemStack clicked = e.getCurrentItem();
        if (!ItemUtils.isCdiscDisc(clicked)) return;

        Inventory top = e.getView().getTopInventory();
        for (int slot : QueueGuiManager.QUEUE_SLOTS) {
            if (top.getItem(slot) == null) {

                ItemStack one = clicked.clone();
                one.setAmount(1);
                top.setItem(slot, one);

                if (clicked.getAmount() > 1) {
                    clicked.setAmount(clicked.getAmount() - 1);
                    e.setCurrentItem(clicked);
                } else {
                    e.setCurrentItem(null);
                }
                return;
            }
        }

    }

    private boolean isPlaceAction(InventoryAction action) {
        return action == InventoryAction.PLACE_ALL
                || action == InventoryAction.PLACE_ONE
                || action == InventoryAction.PLACE_SOME
                || action == InventoryAction.SWAP_WITH_CURSOR;
    }

    private void handleConfirm(InventoryClickEvent e, ConfirmGuiHolder confirm) {
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;

        int raw = e.getRawSlot();
        Block block = confirm.getBlock();
        LavaPlayerManager apm = plugin.getAudioPlayerManager();

        if (raw == QueueGuiManager.CONFIRM_SLOT_YES) {

            if (apm.getGeneration(block) == confirm.getGeneration()) {
                apm.playQueueEntry(block, confirm.getQueueIndex());
            }
            plugin.getQueueGuiManager().open(player, block);
        } else if (raw == QueueGuiManager.CONFIRM_SLOT_NO) {
            plugin.getQueueGuiManager().open(player, block);
        }
    }
}
