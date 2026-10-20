package dev.valkdz.cdisc.gui;

import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class PlayerGuiHolder implements InventoryHolder {

    public enum View {
        MAIN,
        ADVANCED,
        THEMES
    }

    private final Block block;
    private final int generation;
    private final boolean local;
    private final View view;
    private Inventory inventory;

    public PlayerGuiHolder(Block block, int generation, boolean local, View view) {
        this.block = block;
        this.generation = generation;
        this.local = local;
        this.view = view;
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

    public int getGeneration() {
        return generation;
    }

    public View getView() {
        return view;
    }

    public boolean isAdvanced() {
        return view == View.ADVANCED;
    }

    public boolean isLocal() {
        return local;
    }
}
