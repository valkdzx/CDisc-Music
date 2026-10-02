package dev.valkdz.cdisc.feature.broadcast;

import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.UUID;

public final class BroadcastGuiHolder implements InventoryHolder {

    public enum View {
        STATION,
        MIC,
        LEVER,
        BLOCK,
        PASS
    }

    private final View view;
    private final Block jukebox;
    private final UUID mic;
    private final List<UUID> targets;
    private Inventory inventory;

    public BroadcastGuiHolder(View view, Block jukebox, UUID mic, List<UUID> targets) {
        this.view = view;
        this.jukebox = jukebox;
        this.mic = mic;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public View view() {
        return view;
    }

    public Block jukebox() {
        return jukebox;
    }

    public UUID mic() {
        return mic;
    }

    public List<UUID> targets() {
        return targets;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
