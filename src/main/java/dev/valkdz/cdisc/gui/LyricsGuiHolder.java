package dev.valkdz.cdisc.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public class LyricsGuiHolder implements InventoryHolder {

    private final UUID owner;
    private final int page;

    private Inventory inventory;

    public LyricsGuiHolder(UUID owner, int page) {
        this.owner = owner;
        this.page = page;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID getOwner() {
        return owner;
    }

    public int getPage() {
        return page;
    }
}
