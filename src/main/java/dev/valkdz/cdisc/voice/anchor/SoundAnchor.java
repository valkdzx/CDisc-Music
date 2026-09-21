package dev.valkdz.cdisc.voice.anchor;

import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

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

    private static final double MOVED_ENOUGH = 1.0E-4;

    private final Plugin plugin;
    private volatile Entity entity;
    private final AnchorType type;

    private volatile Location sentTo;

    SoundAnchor(Plugin plugin, Entity entity, AnchorType type) {
        this.plugin = plugin;
        this.entity = entity;
        this.type = type;
    }

    public Entity entity() {
        return entity;
    }

    public org.bukkit.World world() {
        return entity.getWorld();
    }

    public boolean inWorld(org.bukkit.World world) {
        return world != null && entity.getWorld().equals(world);
    }

    void replaceEntity(Entity fresh) {
        Entity old = entity;
        entity = fresh;
        sentTo = null;

        Tasks.onEntity(plugin, old, () -> {
            if (old.getVehicle() != null) old.leaveVehicle();
            old.remove();
        });
    }

    public AnchorType type() {
        return type;
    }

    public boolean isAlive() {
        return entity.isValid() && !entity.isDead();
    }

    public void parkAt(Location location) {
        Entity riding = entity;
        Tasks.onEntity(plugin, riding, () -> {
            if (!isAlive()) return;
            if (riding.getVehicle() != null) {
                riding.leaveVehicle();
            }
            sentTo = location.clone();
            Tasks.teleport(riding, location);
        });
    }

    public void rideOn(Entity carrier) {
        Entity rider = entity;
        Tasks.onEntity(plugin, carrier, () -> {
            if (!isAlive()) return;
            if (rider.getVehicle() == carrier) return;
            if (rider.getVehicle() != null) {
                rider.leaveVehicle();
            }
            sentTo = null;
            carrier.addPassenger(rider);
        });
    }

    public void followAt(Location location) {
        // A teleport is a tracker update for every player nearby, and this is called every
        // tick, so a carrier who is standing still must not pay for one.
        if (!moved(location)) return;

        Entity moving = entity;
        Tasks.onEntity(plugin, moving, () -> {
            if (!isAlive()) return;
            if (moving.getVehicle() != null) {
                moving.leaveVehicle();
            }

            sentTo = location.clone();
            Tasks.teleport(moving, location);
        });
    }

    private boolean moved(Location to) {
        if (sentTo == null) return true;

        org.bukkit.World from = sentTo.getWorld();
        return from == null || from != to.getWorld() || sentTo.distanceSquared(to) > MOVED_ENOUGH;
    }

    public void setTeleportSmoothing(int ticks) {
        if (TELEPORT_DURATION == null || !isAlive()) return;
        try {
            TELEPORT_DURATION.invoke(entity, ticks);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
        }
    }

    public void remove() {
        Entity going = entity;
        sentTo = null;
        Tasks.onEntity(plugin, going, () -> {
            if (going.getVehicle() != null) {
                going.leaveVehicle();
            }
            going.remove();
        });
    }
}
