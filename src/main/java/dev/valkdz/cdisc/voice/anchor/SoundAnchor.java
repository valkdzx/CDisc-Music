package dev.valkdz.cdisc.voice.anchor;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

public final class SoundAnchor {

    private static final java.lang.reflect.Method TELEPORT_DURATION = resolveTeleportDuration();

    private static java.lang.reflect.Method resolveTeleportDuration() {
        try {
            return Class.forName("org.bukkit.entity.Display")
                    .getMethod("setTeleportDuration", int.class);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private final Entity entity;
    private final AnchorType type;

    SoundAnchor(Entity entity, AnchorType type) {
        this.entity = entity;
        this.type = type;
    }

    public Entity entity() {
        return entity;
    }

    public AnchorType type() {
        return type;
    }

    public boolean isAlive() {
        return entity.isValid() && !entity.isDead();
    }

    public void parkAt(Location location) {
        if (!isAlive()) return;
        if (entity.getVehicle() != null) {
            entity.leaveVehicle();
        }
        entity.teleport(location);
    }

    public void rideOn(Entity carrier) {
        if (!isAlive()) return;
        if (entity.getVehicle() == carrier) return;
        if (entity.getVehicle() != null) {
            entity.leaveVehicle();
        }
        carrier.addPassenger(entity);
    }

    public void followAt(Location location) {
        if (!isAlive()) return;
        if (entity.getVehicle() != null) {
            entity.leaveVehicle();
        }
        entity.teleport(location);
    }

    public void setTeleportSmoothing(int ticks) {
        if (TELEPORT_DURATION == null || !isAlive()) return;
        try {
            TELEPORT_DURATION.invoke(entity, ticks);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
        }
    }

    public void remove() {
        if (entity.getVehicle() != null) {
            entity.leaveVehicle();
        }
        entity.remove();
    }
}
