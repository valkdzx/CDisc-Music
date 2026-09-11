package dev.valkdz.cdisc.gui;

import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class PairGuiHolder implements InventoryHolder {

    public enum Mode {

        MANAGE,

        PICKER,

        SETTINGS
    }

    private final Mode mode;

    private final Block anchor;

    private final Block subject;

    private final List<Block> targets = new ArrayList<>();
    private Inventory inventory;

    public PairGuiHolder(Mode mode, Block anchor) {
        this(mode, anchor, null);
    }

    public PairGuiHolder(Mode mode, Block anchor, Block subject) {
        this.mode = mode;
        this.anchor = anchor;
        this.subject = subject;
    }

    public Block getSubject() {
        return subject;
    }

    public Mode getMode() {
        return mode;
    }

    public Block getAnchor() {
        return anchor;
    }

    public void setTargets(List<Block> blocks) {
        targets.clear();
        targets.addAll(blocks);
    }

    public Block targetAt(int index) {
        return index < 0 || index >= targets.size() ? null : targets.get(index);
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
