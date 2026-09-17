package dev.valkdz.cdisc.util;

import org.bukkit.entity.Entity;

import java.lang.reflect.Method;

public final class DisplayCompat {

    private static final Method TELEPORT_DURATION = resolve();

    private DisplayCompat() {
    }

    private static Method resolve() {
        try {
            return Class.forName("org.bukkit.entity.Display")
                    .getMethod("setTeleportDuration", int.class);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static void setTeleportDuration(Entity entity, int ticks) {
        if (TELEPORT_DURATION == null || entity == null || entity.isDead()) return;
        try {
            TELEPORT_DURATION.invoke(entity, ticks);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
        }
    }
}
