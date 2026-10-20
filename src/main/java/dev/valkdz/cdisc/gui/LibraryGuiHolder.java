package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.feature.library.TrackLibrary;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public final class LibraryGuiHolder implements InventoryHolder {

    private final UUID owner;
    private int page;
    private String selected;
    private List<TrackLibrary.Track> shown = List.of();
    private Inventory inventory;

    public LibraryGuiHolder(UUID owner) {
        this.owner = owner;
    }

    public UUID getOwner() {
        return owner;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public String getSelected() {
        return selected;
    }

    public void setSelected(String query) {
        this.selected = query;
    }

    public TrackLibrary.Track trackAt(int slot) {
        return slot >= 0 && slot < shown.size() ? shown.get(slot) : null;
    }

    void show(List<TrackLibrary.Track> tracks) {
        this.shown = List.copyOf(tracks);
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
