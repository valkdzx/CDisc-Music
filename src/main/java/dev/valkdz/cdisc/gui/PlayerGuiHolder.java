package dev.valkdz.cdisc.gui;

import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class PlayerGuiHolder implements InventoryHolder {

    private final Block block;
    private final int generation;
    private final boolean local;
    private Inventory inventory;

    public PlayerGuiHolder(Block block, int generation, boolean local) {
        this.block = block;
        this.generation = generation;
        this.local = local;
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

    public boolean isLocal() {
        return local;
    }
}
