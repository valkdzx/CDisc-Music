package dev.valkdz.cdisc.net;

import io.netty.channel.Channel;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public final class PlayerChannelUtils {

    private static final Logger LOGGER = Logger.getLogger("CDisc");

    private static volatile Resolver resolver;
    private static volatile boolean resolutionFailed = false;

    private PlayerChannelUtils() {}

    public static Channel getChannel(Player player) {
        if (resolutionFailed) return null;

        try {
            if (resolver == null) {
                synchronized (PlayerChannelUtils.class) {
                    if (resolver == null) {
                        resolver = buildResolver(player);
                        if (resolver == null) {
                            resolutionFailed = true;
                            return null;
                        }
                    }
                }
            }
            return resolver.resolve(player);
        } catch (Exception e) {
            LOGGER.warning("[CDisc] Failed to resolve player channel via reflection: " + e);
            resolutionFailed = true;
            return null;
        }
    }

    public static boolean isUnsupported() {
        return resolutionFailed;
    }

    private static Resolver buildResolver(Player samplePlayer) throws Exception {
        Method getHandle = findNoArgMethodByName(samplePlayer.getClass(), "getHandle");
        if (getHandle == null) return null;
        getHandle.setAccessible(true);

        Object nmsPlayer = getHandle.invoke(samplePlayer);
        if (nmsPlayer == null) return null;

        FieldPath path = findPathToFieldOfType(nmsPlayer, Channel.class, 4);
        if (path == null) return null;

        return new Resolver(getHandle, path);
    }

    private static FieldPath findPathToFieldOfType(Object root, Class<?> targetType, int maxDepth) {
        return search(root, targetType, maxDepth, new HashSet<>());
    }

    private static FieldPath search(Object current, Class<?> targetType, int depthLeft, Set<Object> visited) {
        if (current == null) return null;
        if (!visited.add(current)) return null;

        Class<?> klass = current.getClass();

        for (Field f : allFields(klass)) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (targetType.isAssignableFrom(f.getType())) {
                if (!tryMakeAccessible(f)) continue;
                return new FieldPath(new Field[]{f});
            }
        }

        if (depthLeft <= 0) return null;

        for (Field f : allFields(klass)) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (isTrivial(f.getType())) continue;
            if (isOutOfScope(f.getType())) continue;
            if (!tryMakeAccessible(f)) continue;

            Object value;
            try {
                value = f.get(current);
            } catch (Exception e) {
                continue;
            }
            if (value == null) continue;
            if (isOutOfScope(value.getClass())) continue;

            FieldPath sub;
            try {
                sub = search(value, targetType, depthLeft - 1, visited);
            } catch (Exception e) {
                continue;
            }
            if (sub != null) {
                Field[] combined = new Field[sub.chain.length + 1];
                combined[0] = f;
                System.arraycopy(sub.chain, 0, combined, 1, sub.chain.length);
                return new FieldPath(combined);
            }
        }
        return null;
    }

    private static boolean isOutOfScope(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    private static boolean tryMakeAccessible(Field f) {
        try {
            f.setAccessible(true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTrivial(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type.isEnum()
                || Number.class.isAssignableFrom(type)
                || Boolean.class.isAssignableFrom(type)
                || type.isArray();
    }

    private static Field[] allFields(Class<?> klass) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = klass;
        while (c != null && c != Object.class) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
            c = c.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }

    private static Method findNoArgMethodByName(Class<?> klass, String name) {
        Class<?> c = klass;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private record FieldPath(Field[] chain) {}

    private record Resolver(Method getHandle, FieldPath path) {
        Channel resolve(Player player) throws Exception {
            Object cur = getHandle.invoke(player);
            for (Field f : path.chain()) {
                if (cur == null) return null;
                cur = f.get(cur);
            }
            return cur instanceof Channel ? (Channel) cur : null;
        }
    }
}
