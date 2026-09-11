package dev.valkdz.cdisc.gui;

import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ConfirmGuiHolder implements InventoryHolder {

    private final Block block;
    private final int generation;
    private final int queueIndex;
    private Inventory inventory;

    public ConfirmGuiHolder(Block block, int generation, int queueIndex) {
        this.block = block;
        this.generation = generation;
        this.queueIndex = queueIndex;
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

    public int getQueueIndex() {
        return queueIndex;
    }
}
