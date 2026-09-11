package dev.valkdz.cdisc.speaker;

import org.bukkit.Location;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class SpeakerGroup {

    private final UUID id;
    private volatile String name;
    private volatile UUID owner;
    private volatile Location main;
    private final Set<Location> speakers = new LinkedHashSet<>();

    public SpeakerGroup(UUID id, String name, UUID owner, Location main) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.main = main;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID owner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    public Location main() {
        return main;
    }

    public synchronized Set<Location> speakers() {
        return new LinkedHashSet<>(speakers);
    }

    public synchronized int size() {
        return speakers.size() + 1;
    }

    public synchronized boolean addSpeaker(Location location) {
        if (SpeakerGroupManager.sameBlock(location, main)) return false;
        return speakers.add(location.getBlock().getLocation());
    }

    public synchronized boolean removeSpeaker(Location location) {
        return speakers.removeIf(l -> SpeakerGroupManager.sameBlock(l, location));
    }

    public synchronized boolean contains(Location location) {
        if (SpeakerGroupManager.sameBlock(location, main)) return true;
        return speakers.stream().anyMatch(l -> SpeakerGroupManager.sameBlock(l, location));
    }

    public synchronized boolean isMain(Location location) {
        return SpeakerGroupManager.sameBlock(location, main);
    }

    public synchronized boolean promote(Location location) {
        if (SpeakerGroupManager.sameBlock(location, main)) return true;
        if (!speakers.removeIf(l -> SpeakerGroupManager.sameBlock(l, location))) return false;
        speakers.add(main);
        main = location.getBlock().getLocation();
        return true;
    }
}
