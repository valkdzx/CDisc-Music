package dev.valkdz.cdisc.broadcast;

import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BroadcastStation {

    public static final int MAX_MICS = 6;

    private final Block jukebox;
    private final String name;
    private final UUID host;
    private final String hostName;
    private final List<Microphone> mics = new CopyOnWriteArrayList<>();
    private volatile List<UUID> anchorIds = List.of();

    public BroadcastStation(Block jukebox, String name, UUID host, String hostName) {
        this.jukebox = jukebox;
        this.name = name;
        this.host = host;
        this.hostName = hostName;
    }

    public Block jukebox() {
        return jukebox;
    }

    public String name() {
        return name;
    }

    public UUID host() {
        return host;
    }

    public String hostName() {
        return hostName;
    }

    public List<Microphone> mics() {
        return mics;
    }

    public Microphone mic(UUID id) {
        for (Microphone mic : mics) {
            if (mic.id().equals(id)) return mic;
        }
        return null;
    }

    public boolean full() {
        return mics.size() >= MAX_MICS;
    }

    public MicColor freeColor() {
        Set<MicColor> free = EnumSet.allOf(MicColor.class);
        for (Microphone mic : mics) free.remove(mic.color());
        return free.isEmpty() ? null : free.iterator().next();
    }

    List<UUID> anchorIds() {
        return anchorIds;
    }

    void setAnchorIds(List<UUID> ids) {
        this.anchorIds = List.copyOf(ids);
    }
}
