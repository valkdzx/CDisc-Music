package dev.valkdz.cdisc.gui;

import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class QueueGuiHolder implements InventoryHolder {

    private final Block block;
    private final int generation;
    private Inventory inventory;

    public QueueGuiHolder(Block block, int generation) {
        this.block = block;
        this.generation = generation;
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
}
