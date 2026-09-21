package dev.valkdz.cdisc.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class Tasks {

    public interface Handle {
        void cancel();
    }

    // Folia's schedulers are reached by reflection on purpose: the plugin still
    // compiles against spigot-api 1.20, which has none of these types.
    private static final boolean FOLIA = probeFolia();

    private static final Object GLOBAL = scheduler("getGlobalRegionScheduler");
    private static final Object REGION = scheduler("getRegionScheduler");
    private static final Object ASYNC = scheduler("getAsyncScheduler");

    private static final Method GLOBAL_RUN =
            on("GlobalRegionScheduler", "run", Plugin.class, Consumer.class);
    private static final Method GLOBAL_LATER =
            on("GlobalRegionScheduler", "runDelayed", Plugin.class, Consumer.class, long.class);
    private static final Method GLOBAL_TIMER =
            on("GlobalRegionScheduler", "runAtFixedRate", Plugin.class, Consumer.class,
                    long.class, long.class);

    private static final Method REGION_RUN =
            on("RegionScheduler", "run", Plugin.class, Location.class, Consumer.class);
    private static final Method REGION_LATER =
            on("RegionScheduler", "runDelayed", Plugin.class, Location.class, Consumer.class,
                    long.class);

    private static final Method ENTITY_SCHEDULER = entityScheduler();
    private static final Method ENTITY_RUN =
            on("EntityScheduler", "run", Plugin.class, Consumer.class, Runnable.class);
    private static final Method ENTITY_LATER =
            on("EntityScheduler", "runDelayed", Plugin.class, Consumer.class, Runnable.class,
                    long.class);
    private static final Method ENTITY_TIMER =
            on("EntityScheduler", "runAtFixedRate", Plugin.class, Consumer.class, Runnable.class,
                    long.class, long.class);

    private static final Method ASYNC_NOW =
            on("AsyncScheduler", "runNow", Plugin.class, Consumer.class);
    private static final Method ASYNC_LATER =
            on("AsyncScheduler", "runDelayed", Plugin.class, Consumer.class, long.class,
                    TimeUnit.class);
    private static final Method ASYNC_TIMER =
            on("AsyncScheduler", "runAtFixedRate", Plugin.class, Consumer.class, long.class,
                    long.class, TimeUnit.class);

    private static final Method TASK_CANCEL = on("ScheduledTask", "cancel");

    private static final Method TELEPORT_ASYNC = teleportAsync();

    private static final Method OWNS_LOCATION = ownerCheck(Location.class);
    private static final Method OWNS_ENTITY = ownerCheck(Entity.class);

    private Tasks() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    public static Handle global(Plugin plugin, Runnable work) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTask(plugin, work));
        return call(GLOBAL, GLOBAL_RUN, plugin, consumer(work));
    }

    public static Handle globalLater(Plugin plugin, Runnable work, long delayTicks) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTaskLater(plugin, work, delayTicks));
        return call(GLOBAL, GLOBAL_LATER, plugin, consumer(work), atLeastOne(delayTicks));
    }

    public static Handle globalTimer(Plugin plugin, Runnable work, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskTimer(plugin, work, delayTicks, periodTicks));
        }
        return call(GLOBAL, GLOBAL_TIMER, plugin, consumer(work),
                atLeastOne(delayTicks), atLeastOne(periodTicks));
    }

    public static Handle region(Plugin plugin, Block block, Runnable work) {
        return region(plugin, block.getLocation(), work);
    }

    public static Handle region(Plugin plugin, Location where, Runnable work) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTask(plugin, work));
        return call(REGION, REGION_RUN, plugin, where, consumer(work));
    }

    public static Handle regionLater(Plugin plugin, Location where, Runnable work, long delayTicks) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTaskLater(plugin, work, delayTicks));
        return call(REGION, REGION_LATER, plugin, where, consumer(work), atLeastOne(delayTicks));
    }

    public static Handle entity(Plugin plugin, Entity entity, Runnable work) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTask(plugin, work));
        return call(schedulerOf(entity), ENTITY_RUN, plugin, consumer(work), null);
    }

    public static Handle entityLater(Plugin plugin, Entity entity, Runnable work, long delayTicks) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTaskLater(plugin, work, delayTicks));
        return call(schedulerOf(entity), ENTITY_LATER, plugin, consumer(work), null,
                atLeastOne(delayTicks));
    }

    public static Handle entityTimer(Plugin plugin, Entity entity, Runnable work,
                                     long delayTicks, long periodTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskTimer(plugin, work, delayTicks, periodTicks));
        }
        return call(schedulerOf(entity), ENTITY_TIMER, plugin, consumer(work), null,
                atLeastOne(delayTicks), atLeastOne(periodTicks));
    }

    // For work a caller already holds the right thread for: runs inline instead of
    // deferring a tick, so a sync driver loop keeps behaving as it did on Spigot.
    public static void inRegion(Plugin plugin, Location where, Runnable work) {
        if (owns(where)) {
            work.run();
            return;
        }
        region(plugin, where, work);
    }

    public static void inRegion(Plugin plugin, Block block, Runnable work) {
        inRegion(plugin, block.getLocation(), work);
    }

    public static void onEntity(Plugin plugin, Entity entity, Runnable work) {
        if (owns(entity)) {
            work.run();
            return;
        }
        entity(plugin, entity, work);
    }

    public static Handle async(Plugin plugin, Runnable work) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTaskAsynchronously(plugin, work));
        return call(ASYNC, ASYNC_NOW, plugin, consumer(work));
    }

    public static Handle asyncLater(Plugin plugin, Runnable work, long delayTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, work, delayTicks));
        }
        return call(ASYNC, ASYNC_LATER, plugin, consumer(work),
                atLeastOne(delayTicks) * 50L, TimeUnit.MILLISECONDS);
    }

    public static Handle asyncTimer(Plugin plugin, Runnable work, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler()
                    .runTaskTimerAsynchronously(plugin, work, delayTicks, periodTicks));
        }
        return call(ASYNC, ASYNC_TIMER, plugin, consumer(work),
                atLeastOne(delayTicks) * 50L, atLeastOne(periodTicks) * 50L, TimeUnit.MILLISECONDS);
    }

    // Folia refuses a plain teleport that leaves the current region, so the async
    // form is used wherever the server has one.
    public static void teleport(Entity entity, Location where) {
        if (TELEPORT_ASYNC == null) {
            entity.teleport(where);
            return;
        }
        invoke(entity, TELEPORT_ASYNC, where);
    }

    public static boolean owns(Location where) {
        if (!FOLIA) return Bukkit.isPrimaryThread();
        Object answer = invoke(null, OWNS_LOCATION, where);
        return Boolean.TRUE.equals(answer);
    }

    public static boolean owns(Entity entity) {
        if (!FOLIA) return Bukkit.isPrimaryThread();
        Object answer = invoke(null, OWNS_ENTITY, entity);
        return Boolean.TRUE.equals(answer);
    }

    private static Object schedulerOf(Entity entity) {
        if (ENTITY_SCHEDULER == null || entity == null) return null;
        try {
            return ENTITY_SCHEDULER.invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Consumer<Object> consumer(Runnable work) {
        return ignored -> work.run();
    }

    private static long atLeastOne(long ticks) {
        return Math.max(1L, ticks);
    }

    private static Handle call(Object target, Method method, Object... args) {
        Object task = invoke(target, method, args);
        return () -> invoke(task, TASK_CANCEL);
    }

    private static Object invoke(Object target, Method method, Object... args) {
        if (method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Handle wrap(org.bukkit.scheduler.BukkitTask task) {
        return task::cancel;
    }

    private static boolean probeFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static Object scheduler(String getter) {
        if (!FOLIA) return null;
        try {
            return Bukkit.class.getMethod(getter).invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Method on(String type, String name, Class<?>... parameters) {
        if (!FOLIA) return null;
        try {
            return Class.forName("io.papermc.paper.threadedregions.scheduler." + type)
                    .getMethod(name, parameters);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Method entityScheduler() {
        if (!FOLIA) return null;
        try {
            return Entity.class.getMethod("getScheduler");
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Method teleportAsync() {
        if (!FOLIA) return null;
        try {
            return Entity.class.getMethod("teleportAsync", Location.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Method ownerCheck(Class<?> parameter) {
        if (!FOLIA) return null;
        try {
            return Bukkit.class.getMethod("isOwnedByCurrentRegion", parameter);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
