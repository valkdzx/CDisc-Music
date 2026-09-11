package dev.valkdz.cdisc.gui;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlaylistGuiHolder implements InventoryHolder {

    private final String query;
    private final String playlistName;
    private final List<AudioTrack> tracks;

    private final Map<Integer, ItemStack> placed = new HashMap<>();

    private int page;
    private Inventory inventory;

    public PlaylistGuiHolder(String query, String playlistName, List<AudioTrack> tracks) {
        this.query = query;
        this.playlistName = playlistName;
        this.tracks = tracks;
    }

    public String getQuery() {
        return query;
    }

    public String getPlaylistName() {
        return playlistName;
    }

    public List<AudioTrack> getTracks() {
        return tracks;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = Math.max(0, Math.min(page, lastPage()));
    }

    public int lastPage() {
        return Math.max(0, (tracks.size() - 1) / PlaylistGuiManager.SLOTS_PER_PAGE);
    }

    public boolean hasPages() {
        return lastPage() > 0;
    }

    public int slotsOnPage() {
        int remaining = tracks.size() - page * PlaylistGuiManager.SLOTS_PER_PAGE;
        return Math.max(0, Math.min(PlaylistGuiManager.SLOTS_PER_PAGE, remaining));
    }

    public int trackIndexAt(int slot) {
        if (slot < 0 || slot >= slotsOnPage()) return -1;
        return page * PlaylistGuiManager.SLOTS_PER_PAGE + slot;
    }

    public ItemStack placedAt(int trackIndex) {
        return placed.get(trackIndex);
    }

    public void place(int trackIndex, ItemStack disc) {
        if (disc == null || disc.getType().isAir()) {
            placed.remove(trackIndex);
        } else {
            placed.put(trackIndex, disc);
        }
    }

    public Map<Integer, ItemStack> allPlaced() {
        return placed;
    }

    public int firstEmpty() {
        for (int i = 0; i < tracks.size(); i++) {
            if (!placed.containsKey(i)) return i;
        }
        return -1;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
