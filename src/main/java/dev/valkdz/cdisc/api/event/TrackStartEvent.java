package dev.valkdz.cdisc.api.event;

import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class TrackStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum Cause {
        DISC,
        QUEUE,
        CROSSFADE,
        REPEAT
    }

    private final Block jukebox;
    private final String title;
    private final String author;
    private final String uri;
    private final long durationMs;
    private final boolean live;
    private final String source;
    private final Cause cause;

    public TrackStartEvent(Block jukebox, String title, String author, String uri, long durationMs,
                           boolean live, String source, Cause cause) {
        this.jukebox = jukebox;
        this.title = title;
        this.author = author;
        this.uri = uri;
        this.durationMs = durationMs;
        this.live = live;
        this.source = source;
        this.cause = cause;
    }

    public Block getJukebox() {
        return jukebox;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public String getUri() {
        return uri;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public boolean isLive() {
        return live;
    }

    public String getSource() {
        return source;
    }

    public Cause getCause() {
        return cause;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
