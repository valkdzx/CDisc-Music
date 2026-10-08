package dev.valkdz.cdisc.api;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.gui.PlayerGuiHolder;
import dev.valkdz.cdisc.gui.QueueGuiHolder;
import dev.valkdz.cdisc.gui.dialog.Dialogs;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

import java.util.Objects;

final class ApiGui implements GuiControl {

    private final Main plugin;

    ApiGui(Main plugin) {
        this.plugin = plugin;
    }

    private static Main cdisc() {
        Main cdisc = Main.getInstance();
        if (cdisc == null || !cdisc.isEnabled()) throw new IllegalStateException("CDisc is not enabled");
        return cdisc;
    }

    @Override
    public void open(Player player, Screen screen, Block jukebox) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(screen, "screen");
        Main cdisc = cdisc();
        if (screen.needsJukebox() && (jukebox == null || jukebox.getType() != Material.JUKEBOX)) {
            throw new IllegalArgumentException("not a jukebox: " + jukebox);
        }
        if (screen == Screen.BROADCAST
                && (cdisc.getBroadcastManager() == null || cdisc.getBroadcastManager().station(jukebox) == null)) {
            throw new IllegalStateException("That jukebox is not a broadcast station");
        }
        if (screen == Screen.SETTINGS && !Dialogs.supported()) {
            throw new IllegalStateException("This server has no dialog API for the settings window");
        }

        Tasks.onEntity(plugin, player, () -> {
            if (!player.isOnline()) return;
            switch (screen) {
                case PLAYER -> cdisc.getPlayerGuiManager().open(player, jukebox);
                case ADVANCED -> cdisc.getPlayerGuiManager().openAdvanced(player, jukebox);
                case QUEUE -> cdisc.getQueueGuiManager().open(player, jukebox);
                case SPEAKERS -> cdisc.getPlayerActions().openPair(player, jukebox);
                case BROADCAST -> cdisc.getBroadcastGui().openStation(player, jukebox);
                case LYRICS_LOOK -> cdisc.getPlayerActions().openMyLyricsLook(player);
                case SETTINGS -> Dialogs.openConfig(cdisc, player);
            }
        });
    }

    @Override
    public void open(Player player, Screen screen) {
        if (screen != null && screen.needsJukebox()) {
            throw new IllegalArgumentException(screen + " needs a jukebox");
        }
        open(player, screen, null);
    }

    @Override
    public void openPlaylist(Player player, String source) {
        Objects.requireNonNull(player, "player");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is empty");
        Main cdisc = cdisc();
        Tasks.onEntity(plugin, player, () -> {
            if (player.isOnline()) cdisc.getPlaylistGuiManager().openFor(player, source.trim());
        });
    }

    @Override
    public boolean isOpen(Player player) {
        return player != null && isCdisc(player.getOpenInventory().getTopInventory().getHolder());
    }

    private static boolean isCdisc(InventoryHolder holder) {
        return holder != null && holder.getClass().getName().startsWith("dev.valkdz.cdisc.");
    }

    @Override
    public void close(Player player) {
        Objects.requireNonNull(player, "player");
        Tasks.onEntity(plugin, player, () -> {
            if (isOpen(player)) player.closeInventory();
        });
    }

    @Override
    public void closeAll(Block jukebox) {
        Objects.requireNonNull(jukebox, "jukebox");
        Main cdisc = cdisc();
        Tasks.inRegion(plugin, jukebox, () -> cdisc.getQueueGuiManager().forceCloseFor(jukebox));
        for (Player player : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(plugin, player, () -> {
                InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
                Block shown = holder instanceof PlayerGuiHolder gui ? gui.getBlock()
                        : holder instanceof QueueGuiHolder queue ? queue.getBlock() : null;
                if (jukebox.equals(shown)) player.closeInventory();
            });
        }
    }
}
