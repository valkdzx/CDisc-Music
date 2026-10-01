package dev.valkdz.cdisc.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

public class DiscCreateEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ItemStack item;
    private final String address;
    private final String title;
    private final String author;
    private final long lengthMs;
    private boolean cancelled;

    public DiscCreateEvent(Player player, ItemStack item, String address, String title, String author,
                           long lengthMs) {
        super(player);
        this.item = item;
        this.address = address;
        this.title = title;
        this.author = author;
        this.lengthMs = lengthMs;
    }

    public ItemStack getItem() {
        return item;
    }

    public String getAddress() {
        return address;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public long getLengthMs() {
        return lengthMs;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
