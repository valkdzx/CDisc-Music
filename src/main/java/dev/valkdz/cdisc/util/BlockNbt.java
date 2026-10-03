package dev.valkdz.cdisc.util;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class BlockNbt {

    private static Class<?> entityState;
    private static Method snapshotNbt;
    private static Method fromWorld;
    private static Method loadData;
    private static Method applyTo;
    private static Method legacyLoad;
    private static Method parse;
    private static Method merge;
    private static Method keys;
    private static boolean ready;

    private BlockNbt() {}

    // NMS members are matched by signature, not name: Spigot obfuscates them and
    // the names differ per version, while these shapes have held since 1.20.
    public static boolean init() {
        try {
            String craft = Bukkit.getServer().getClass().getPackage().getName();
            entityState = Class.forName(craft + ".block.CraftBlockEntityState");
            snapshotNbt = entityState.getMethod("getSnapshotNBT");
            Class<?> compound = snapshotNbt.getReturnType();

            fromWorld = declared(entityState, "getBlockEntityFromWorld", "getTileEntityFromWorld");
            Class<?> blockEntity = fromWorld.getReturnType();
            loadData = method(entityState, "loadData", compound);
            if (loadData != null) {
                applyTo = entityState.getDeclaredMethod("applyTo", blockEntity);
                applyTo.setAccessible(true);
            } else {
                legacyLoad = only(blockEntity, m -> m.getReturnType() == void.class
                        && m.getParameterCount() == 1 && m.getParameterTypes()[0] == compound);
            }

            Class<?> parser = firstClass(compound.getPackage().getName() + ".TagParser",
                    compound.getPackage().getName() + ".MojangsonParser");
            parse = only(parser, m -> Modifier.isStatic(m.getModifiers()) && m.getReturnType() == compound
                    && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class);
            merge = only(compound, m -> !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == compound
                    && m.getParameterCount() == 1 && m.getParameterTypes()[0] == compound);
            keys = only(compound, m -> !Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                    && m.getReturnType() == Set.class
                    && m.getGenericReturnType() instanceof ParameterizedType p
                    && p.getActualTypeArguments()[0] == String.class);
            ready = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            ready = false;
        }
        return ready;
    }

    public static boolean isReady() {
        return ready;
    }

    public static String read(Block block) throws Exception {
        BlockState state = block.getState();
        if (!ready || !entityState.isInstance(state)) return null;
        return call(snapshotNbt, state).toString();
    }

    // Writes into the live block entity, not the snapshot, so no update() follows:
    // a jukebox's update() replays its record and the vanilla sound with it.
    public static void merge(Block block, String snbt) throws Exception {
        if (!ready) throw new IllegalStateException("block NBT is not supported on this server");
        BlockState state = block.getState();
        if (!entityState.isInstance(state)) return;
        Object live = call(fromWorld, state);
        if (live == null) return;

        Object tag = call(snapshotNbt, state);
        call(merge, tag, call(parse, null, snbt));
        if (loadData != null) {
            call(loadData, state, tag);
            call(applyTo, state, live);
        } else {
            call(legacyLoad, live, tag);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> keys(String snbt) throws Exception {
        if (!ready) throw new IllegalStateException("block NBT is not supported on this server");
        return new ArrayList<>((Set<String>) call(keys, call(parse, null, snbt)));
    }

    private static Object call(Method m, Object target, Object... args) throws Exception {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    private static Method declared(Class<?> owner, String... names) throws NoSuchMethodException {
        for (String name : names) {
            Method m = method(owner, name);
            if (m != null) return m;
        }
        throw new NoSuchMethodException(owner.getName() + "." + names[0]);
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Method only(Class<?> owner, Predicate<Method> shape) throws NoSuchMethodException {
        Method found = null;
        for (Method m : owner.getMethods()) {
            if (!shape.test(m)) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous in " + owner.getName());
            found = m;
        }
        if (found == null) throw new NoSuchMethodException("no match in " + owner.getName());
        return found;
    }

    private static Class<?> firstClass(String... names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {}
        }
        throw new ClassNotFoundException(names[0]);
    }
}
