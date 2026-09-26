package dev.valkdz.cdisc.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class LocalConfigGuiHolder implements InventoryHolder {

    public enum Mode {
        MAIN,
        LYRICS
    }

    private final Mode mode;
    private final String file;
    private final int page;
    private final boolean clearArmed;
    private Inventory inventory;

    public LocalConfigGuiHolder(Mode mode, String file, int page, boolean clearArmed) {
        this.mode = mode;
        this.file = file;
        this.page = page;
        this.clearArmed = clearArmed;
    }

    public Mode getMode() {
        return mode;
    }

    public String getFile() {
        return file;
    }

    public int getPage() {
        return page;
    }

    public boolean isClearArmed() {
        return clearArmed;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
