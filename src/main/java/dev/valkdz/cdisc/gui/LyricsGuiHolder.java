package dev.valkdz.cdisc.gui;

import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public class LyricsGuiHolder implements InventoryHolder {

    private final Block block;
    private final UUID owner;
    private final int page;

    private Inventory inventory;

    private LyricsGuiHolder(Block block, UUID owner, int page) {
        this.block = block;
        this.owner = owner;
        this.page = page;
    }

    public static LyricsGuiHolder forJukebox(Block block, int page) {
        return new LyricsGuiHolder(block, null, page);
    }

    public static LyricsGuiHolder forPreset(UUID owner, int page) {
        return new LyricsGuiHolder(null, owner, page);
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Block getBlock() {
        return block;
    }

    public UUID getOwner() {
        return owner;
    }

    public int getPage() {
        return page;
    }

    public boolean isPreset() {
        return owner != null;
    }
}
