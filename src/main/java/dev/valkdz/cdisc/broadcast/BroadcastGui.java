package dev.valkdz.cdisc.broadcast;

import dev.valkdz.cdisc.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BroadcastGui implements Listener {

    private static final int STATION_INFO = 0;
    private static final int STATION_FIRST_MIC = 1;
    private static final int STATION_BACK = 9;
    private static final int STATION_NEW_HANDHELD = 11;
    private static final int STATION_NEW_BLOCK = 13;
    private static final int EXIT = 17;

    private static final int MIC_INFO = 0;
    private static final int MIC_VOLUME = 2;
    private static final int MIC_MUTE = 3;
    private static final int MIC_HOLDER = 5;
    private static final int MIC_REMOVE = 7;
    private static final int MIC_BACK = 9;

    private static final int LEVER_VOLUME = 1;
    private static final int LEVER_MUTE = 2;
    private static final int LEVER_PASS = 3;
    private static final int LEVER_TO_BLOCK = 4;
    private static final int LEVER_RETURN = 5;
    private static final int LEVER_EXIT = 7;
    private static final int GUEST_MUTE = 2;
    private static final int GUEST_RETURN = 4;
    private static final int GUEST_EXIT = 6;

    private static final int BLOCK_INFO = 0;
    private static final int BLOCK_VOLUME = 2;
    private static final int BLOCK_MUTE = 3;
    private static final int BLOCK_GUEST_MUTE = 4;
    private static final int BLOCK_TO_HANDHELD = 5;
    private static final int BLOCK_REMOVE = 6;
    private static final int BLOCK_EXIT = 8;

    private static final int PASS_SIZE = 54;
    private static final int PASS_BACK = 45;
    private static final int PASS_EXIT = 53;

    private static final int VOLUME_STEP = 10;

    private final Main plugin;

    public BroadcastGui(Main plugin) {
        this.plugin = plugin;
    }

    private BroadcastManager manager() {
        return plugin.getBroadcastManager();
    }

    private String msg(Player player, String key, Object... args) {
        return plugin.getMessageManager().get(player, key, args);
    }

    public void openStation(Player player, Block jukebox) {
        BroadcastStation station = manager().station(jukebox);
        if (!manager().canControl(player, station)) {
            player.sendMessage("§c" + msg(player, "broadcast.not_host"));
            return;
        }

        BroadcastGuiHolder holder = new BroadcastGuiHolder(BroadcastGuiHolder.View.STATION, jukebox, null, null);
        Inventory inventory = create(holder, 18, msg(player, "broadcast.gui.title", station.name()));

        inventory.setItem(STATION_INFO, item(Material.LECTERN, msg(player, "broadcast.gui.info", station.name()),
                msg(player, "broadcast.gui.info_host", station.hostName()),
                msg(player, "broadcast.gui.info_count", station.mics().size(), BroadcastStation.MAX_MICS)));

        List<Microphone> mics = station.mics();
        for (int i = 0; i < BroadcastStation.MAX_MICS; i++) {
            inventory.setItem(STATION_FIRST_MIC + i, i < mics.size()
                    ? micItem(player, mics.get(i), msg(player, "broadcast.gui.open_hint"))
                    : item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, msg(player, "broadcast.gui.free_slot")));
        }

        inventory.setItem(STATION_BACK, item(Material.ARROW, msg(player, "broadcast.gui.back")));
        inventory.setItem(STATION_NEW_HANDHELD, item(Material.LEVER, msg(player, "broadcast.gui.new_handheld"),
                msg(player, "broadcast.gui.new_handheld_lore")));
        inventory.setItem(STATION_NEW_BLOCK, item(Material.NOTE_BLOCK, msg(player, "broadcast.gui.new_block"),
                msg(player, "broadcast.gui.new_block_lore", plugin.cdiscConfig().getBroadcastBlockRange())));
        inventory.setItem(EXIT, exit(player));
        player.openInventory(inventory);
    }

    public void openMic(Player player, BroadcastStation station, Microphone mic) {
        if (!manager().canControl(player, station) || !manager().isLive(station, mic)) {
            openStation(player, station.jukebox());
            return;
        }

        BroadcastGuiHolder holder = new BroadcastGuiHolder(BroadcastGuiHolder.View.MIC, station.jukebox(), mic.id(), null);
        Inventory inventory = create(holder, 18, micName(player, mic));

        inventory.setItem(MIC_INFO, micItem(player, mic, null));
        inventory.setItem(MIC_VOLUME, volumeItem(player, mic));
        inventory.setItem(MIC_MUTE, item(mic.muted() ? Material.GRAY_DYE : Material.LIME_DYE,
                msg(player, mic.muted() ? "broadcast.gui.muted" : "broadcast.gui.live"),
                msg(player, "broadcast.gui.mute_hint")));

        if (mic.mode() == Microphone.Mode.HANDHELD) {
            UUID holderId = mic.holder();
            if (holderId == null) {
                inventory.setItem(MIC_HOLDER, item(Material.LEVER, msg(player, "broadcast.gui.take")));
            } else if (!holderId.equals(player.getUniqueId())) {
                inventory.setItem(MIC_HOLDER, item(Material.LEAD, msg(player, "broadcast.gui.take_back"),
                        msg(player, "broadcast.gui.holder", nameOf(holderId))));
            }
        } else {
            inventory.setItem(MIC_HOLDER, item(Material.LEVER, msg(player, "broadcast.gui.to_handheld"),
                    msg(player, "broadcast.gui.to_handheld_lore")));
        }
        inventory.setItem(MIC_REMOVE, item(Material.RED_CONCRETE, msg(player, "broadcast.gui.remove")));
        inventory.setItem(MIC_BACK, item(Material.ARROW, msg(player, "broadcast.gui.back")));
        inventory.setItem(EXIT, exit(player));
        player.openInventory(inventory);
    }

    public void openLever(Player player, BroadcastStation station, Microphone mic) {
        BroadcastGuiHolder holder = new BroadcastGuiHolder(BroadcastGuiHolder.View.LEVER, station.jukebox(), mic.id(), null);
        Inventory inventory = create(holder, 9, msg(player, "broadcast.gui.lever_title"));

        ItemStack giveBack = item(Material.JUKEBOX, msg(player, "broadcast.gui.give_back"),
                msg(player, "broadcast.gui.give_back_lore"));
        if (manager().canControl(player, station)) {
            inventory.setItem(LEVER_VOLUME, volumeItem(player, mic));
            inventory.setItem(LEVER_MUTE, muteItem(player, mic));
            inventory.setItem(LEVER_PASS, item(Material.ENDER_PEARL, msg(player, "broadcast.gui.pass"),
                    msg(player, "broadcast.gui.pass_lore", plugin.cdiscConfig().getBroadcastHandheldRange())));
            inventory.setItem(LEVER_TO_BLOCK, item(Material.NOTE_BLOCK, msg(player, "broadcast.gui.to_block"),
                    msg(player, "broadcast.gui.to_block_lore", plugin.cdiscConfig().getBroadcastBlockRange())));
            inventory.setItem(LEVER_RETURN, giveBack);
            inventory.setItem(LEVER_EXIT, exit(player));
        } else {
            inventory.setItem(GUEST_MUTE, muteItem(player, mic));
            inventory.setItem(GUEST_RETURN, giveBack);
            inventory.setItem(GUEST_EXIT, exit(player));
        }
        player.openInventory(inventory);
    }

    public void openBlock(Player player, BroadcastStation station, Microphone mic) {
        BroadcastGuiHolder holder = new BroadcastGuiHolder(BroadcastGuiHolder.View.BLOCK, station.jukebox(), mic.id(), null);
        Inventory inventory = create(holder, 9, micName(player, mic));

        inventory.setItem(BLOCK_INFO, micItem(player, mic, null));
        if (manager().canControl(player, station)) {
            inventory.setItem(BLOCK_VOLUME, volumeItem(player, mic));
            inventory.setItem(BLOCK_MUTE, muteItem(player, mic));
            inventory.setItem(BLOCK_TO_HANDHELD, item(Material.LEVER, msg(player, "broadcast.gui.to_handheld"),
                    msg(player, "broadcast.gui.to_handheld_lore")));
            inventory.setItem(BLOCK_REMOVE, item(Material.RED_CONCRETE, msg(player, "broadcast.gui.remove")));
        } else {
            inventory.setItem(BLOCK_GUEST_MUTE, muteItem(player, mic));
        }
        inventory.setItem(BLOCK_EXIT, exit(player));
        player.openInventory(inventory);
    }

    public void openPass(Player player, BroadcastStation station, Microphone mic) {
        List<Player> targets = manager().passTargets(player, station);
        List<UUID> ids = new ArrayList<>();
        for (Player target : targets) {
            if (ids.size() >= PASS_BACK) break;
            ids.add(target.getUniqueId());
        }

        BroadcastGuiHolder holder = new BroadcastGuiHolder(BroadcastGuiHolder.View.PASS, station.jukebox(), mic.id(), ids);
        Inventory inventory = create(holder, PASS_SIZE, msg(player, "broadcast.gui.pass_title"));
        for (int i = 0; i < ids.size(); i++) {
            Player target = Bukkit.getPlayer(ids.get(i));
            if (target != null) inventory.setItem(i, head(target));
        }
        if (ids.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, msg(player, "broadcast.gui.pass_nobody")));
        }
        inventory.setItem(PASS_BACK, item(Material.ARROW, msg(player, "broadcast.gui.back")));
        inventory.setItem(PASS_EXIT, exit(player));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BroadcastGuiHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        BroadcastStation station = manager().station(holder.jukebox());
        if (station == null) {
            player.closeInventory();
            player.sendMessage("§c" + msg(player, "broadcast.ended_short"));
            return;
        }
        Microphone mic = holder.mic() == null ? null : station.mic(holder.mic());
        int slot = event.getSlot();

        switch (holder.view()) {
            case STATION -> onStation(player, station, slot);
            case MIC -> {
                if (mic != null) onMic(player, station, mic, slot, event.isRightClick());
            }
            case LEVER -> {
                if (mic != null) onLever(player, station, mic, slot, event.isRightClick());
            }
            case BLOCK -> {
                if (mic != null) onBlock(player, station, mic, slot, event.isRightClick());
            }
            case PASS -> {
                if (mic != null) onPass(player, station, mic, holder, slot);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BroadcastGuiHolder) event.setCancelled(true);
    }

    private void onStation(Player player, BroadcastStation station, int slot) {
        if (!manager().canControl(player, station)) return;

        int index = slot - STATION_FIRST_MIC;
        if (index >= 0 && index < BroadcastStation.MAX_MICS) {
            if (index < station.mics().size()) openMic(player, station, station.mics().get(index));
            return;
        }
        switch (slot) {
            case STATION_BACK -> plugin.getPlayerGuiManager().open(player, station.jukebox());
            case STATION_NEW_HANDHELD -> {
                if (manager().createHandheld(player, station)) openStation(player, station.jukebox());
            }
            case STATION_NEW_BLOCK -> {
                player.closeInventory();
                manager().beginBlockSelection(player, station);
            }
            case EXIT -> player.closeInventory();
            default -> {
            }
        }
    }

    private void onMic(Player player, BroadcastStation station, Microphone mic, int slot, boolean right) {
        if (!manager().canControl(player, station)) return;

        switch (slot) {
            case MIC_VOLUME -> {
                manager().setVolume(station, mic, mic.volume() + (right ? -VOLUME_STEP : VOLUME_STEP));
                openMic(player, station, mic);
            }
            case MIC_MUTE -> {
                manager().toggleMute(station, mic);
                openMic(player, station, mic);
            }
            case MIC_HOLDER -> {
                if (mic.mode() == Microphone.Mode.BLOCK) {
                    player.closeInventory();
                    manager().makeHandheld(player, station, mic);
                    return;
                }
                if (mic.holder() == null) {
                    manager().takeFor(player, station, mic);
                } else if (!mic.holder().equals(player.getUniqueId())) {
                    manager().forceReturn(station, mic);
                }
                openMic(player, station, mic);
            }
            case MIC_REMOVE -> {
                manager().removeMic(station, mic);
                openStation(player, station.jukebox());
            }
            case MIC_BACK -> openStation(player, station.jukebox());
            case EXIT -> player.closeInventory();
            default -> {
            }
        }
    }

    private void onLever(Player player, BroadcastStation station, Microphone mic, int slot, boolean right) {
        if (!player.getUniqueId().equals(mic.holder())) {
            player.closeInventory();
            return;
        }
        if (manager().canControl(player, station)) {
            switch (slot) {
                case LEVER_VOLUME -> {
                    manager().setVolume(station, mic, mic.volume() + (right ? -VOLUME_STEP : VOLUME_STEP));
                    openLever(player, station, mic);
                }
                case LEVER_MUTE -> {
                    manager().toggleMute(station, mic);
                    openLever(player, station, mic);
                }
                case LEVER_PASS -> openPass(player, station, mic);
                case LEVER_TO_BLOCK -> {
                    player.closeInventory();
                    manager().beginMoveToBlock(player, station, mic);
                }
                case LEVER_RETURN -> {
                    player.closeInventory();
                    manager().returnToJukebox(player, station, mic);
                }
                case LEVER_EXIT -> player.closeInventory();
                default -> {
                }
            }
            return;
        }
        switch (slot) {
            case GUEST_MUTE -> {
                manager().toggleMute(station, mic);
                openLever(player, station, mic);
            }
            case GUEST_RETURN -> {
                player.closeInventory();
                manager().returnToJukebox(player, station, mic);
            }
            case GUEST_EXIT -> player.closeInventory();
            default -> {
            }
        }
    }

    private void onBlock(Player player, BroadcastStation station, Microphone mic, int slot, boolean right) {
        if (mic.mode() != Microphone.Mode.BLOCK) {
            player.closeInventory();
            return;
        }
        if (slot == BLOCK_EXIT) {
            player.closeInventory();
            return;
        }
        if (!manager().canControl(player, station)) {
            if (slot == BLOCK_GUEST_MUTE) {
                manager().toggleMute(station, mic);
                openBlock(player, station, mic);
            }
            return;
        }
        switch (slot) {
            case BLOCK_VOLUME -> {
                manager().setVolume(station, mic, mic.volume() + (right ? -VOLUME_STEP : VOLUME_STEP));
                openBlock(player, station, mic);
            }
            case BLOCK_MUTE -> {
                manager().toggleMute(station, mic);
                openBlock(player, station, mic);
            }
            case BLOCK_TO_HANDHELD -> {
                player.closeInventory();
                manager().makeHandheld(player, station, mic);
            }
            case BLOCK_REMOVE -> {
                player.closeInventory();
                manager().removeMic(station, mic);
            }
            default -> {
            }
        }
    }

    private void onPass(Player player, BroadcastStation station, Microphone mic, BroadcastGuiHolder holder, int slot) {
        if (slot == PASS_BACK) {
            openLever(player, station, mic);
            return;
        }
        if (slot == PASS_EXIT) {
            player.closeInventory();
            return;
        }
        if (slot < 0 || slot >= holder.targets().size()) return;

        Player target = Bukkit.getPlayer(holder.targets().get(slot));
        player.closeInventory();
        if (target == null) {
            player.sendMessage("§c" + msg(player, "broadcast.pass_gone"));
            return;
        }
        manager().pass(player, station, mic, target);
    }

    private Inventory create(BroadcastGuiHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inventory);
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < size; slot++) inventory.setItem(slot, filler);
        return inventory;
    }

    private String micName(Player player, Microphone mic) {
        return msg(player, "broadcast.item.name", mic.color().chat(), MicrophoneItems.label(plugin, player, mic));
    }

    private ItemStack micItem(Player player, Microphone mic, String hint) {
        List<String> lore = new ArrayList<>();
        if (mic.mode() == Microphone.Mode.HANDHELD) {
            lore.add(msg(player, "broadcast.gui.mode_handheld"));
            lore.add(mic.holder() == null ? msg(player, "broadcast.gui.holder_none")
                    : msg(player, "broadcast.gui.holder", nameOf(mic.holder())));
        } else {
            Location at = mic.block();
            lore.add(msg(player, "broadcast.gui.mode_block", at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()));
        }
        lore.add(msg(player, "broadcast.gui.volume", mic.volume()));
        lore.add(msg(player, mic.muted() ? "broadcast.gui.muted" : "broadcast.gui.live"));
        if (hint != null) lore.add(hint);
        return item(mic.color().wool(), micName(player, mic), lore.toArray(new String[0]));
    }

    private ItemStack volumeItem(Player player, Microphone mic) {
        return item(Material.NOTE_BLOCK, msg(player, "broadcast.gui.volume", mic.volume()),
                msg(player, "broadcast.gui.volume_hint"));
    }

    private ItemStack muteItem(Player player, Microphone mic) {
        return item(mic.muted() ? Material.GRAY_DYE : Material.LIME_DYE,
                msg(player, mic.muted() ? "broadcast.gui.muted" : "broadcast.gui.live"),
                msg(player, "broadcast.gui.mute_hint"));
    }

    private ItemStack exit(Player player) {
        return item(Material.BARRIER, msg(player, "gui.exit.name"));
    }

    private static String nameOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "?" : name;
    }

    private static ItemStack head(Player target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(target);
            meta.setDisplayName("§f" + target.getName());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }
}
