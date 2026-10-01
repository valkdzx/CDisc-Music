package dev.valkdz.cdisc.api.event;

import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PlaybackStopEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Block jukebox;

    public PlaybackStopEvent(Block jukebox) {
        this.jukebox = jukebox;
    }

    public Block getJukebox() {
        return jukebox;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
