package dev.valkdz.cdisc.gui;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class JukeboxViewers {

    private final Map<Block, UUID> holders = new ConcurrentHashMap<>();

    public boolean heldByOther(Block block, Player player) {
        UUID holder = holders.get(block);
        if (holder == null || holder.equals(player.getUniqueId())) return false;
        Player other = Bukkit.getPlayer(holder);

        return other != null && other.isOnline();
    }

    public boolean isClaimed(Block block) {
        UUID holder = holders.get(block);
        if (holder == null) return false;
        Player player = Bukkit.getPlayer(holder);
        return player != null && player.isOnline();
    }

    public void claim(Block block, Player player) {
        holders.put(block, player.getUniqueId());
    }

    public void release(Block block, Player player) {
        holders.remove(block, player.getUniqueId());
    }

    public void clear(Block block) {
        holders.remove(block);
    }
}
