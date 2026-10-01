package dev.valkdz.cdisc.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public class LyricsGuiHolder implements InventoryHolder {

    private final UUID owner;
    private final int page;
    private final int returnPage;
    private final String serverPreset;

    private Inventory inventory;

    public LyricsGuiHolder(UUID owner, int page) {
        this(owner, page, page);
    }

    public LyricsGuiHolder(UUID owner, int page, int returnPage) {
        this(owner, page, returnPage, null);
    }

    public LyricsGuiHolder(UUID owner, int page, int returnPage, String serverPreset) {
        this.owner = owner;
        this.page = page;
        this.returnPage = returnPage;
        this.serverPreset = serverPreset;
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

    public int getReturnPage() {
        return returnPage;
    }

    public String getServerPreset() {
        return serverPreset;
    }
}
