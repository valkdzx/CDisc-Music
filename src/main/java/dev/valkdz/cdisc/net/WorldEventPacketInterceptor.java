package dev.valkdz.cdisc.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.logging.Logger;

public final class WorldEventPacketInterceptor {

    private static final Logger LOGGER = Logger.getLogger("CDisc");
    private static final String HANDLER_NAME = "cdisc_world_event_listener";

    private static volatile PacketShape shape;
    private static volatile boolean unsupported = false;
    private static final Set<Class<?>> nonMatchingClasses = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private final JavaPlugin plugin;
    private final BiFunction<Player, WorldEventPacket, Boolean> onWorldEvent;
    private final Runnable onUnsupported;
    private final Set<Channel> injected = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public WorldEventPacketInterceptor(JavaPlugin plugin, BiFunction<Player, WorldEventPacket, Boolean> onWorldEvent, Runnable onUnsupported) {
        this.plugin = plugin;
        this.onWorldEvent = onWorldEvent;
        this.onUnsupported = onUnsupported;
    }

    public record WorldEventPacket(int effectId, int x, int y, int z, int data) {}

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onJoin(PlayerJoinEvent e) {
                inject(e.getPlayer());
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onQuit(PlayerQuitEvent e) {}
        }, plugin);

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            inject(p);
        }
    }

    public static boolean isUnsupported() {
        return unsupported;
    }

    private void inject(Player player) {
        if (unsupported) return;

        Channel channel = PlayerChannelUtils.getChannel(player);
        if (channel == null) {
            boolean firstTime = !unsupported;
            unsupported = true;
            LOGGER.warning("[CDisc] Could not locate the network channel for player " + player.getName()
                    + " via reflection. This Minecraft/server version is not supported by CDisc's packet hook.");
            if (firstTime && onUnsupported != null) onUnsupported.run();
            return;
        }

        if (!injected.add(channel)) return;

        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) return;
            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    boolean suppress = false;
                    try {
                        WorldEventPacket decoded = tryDecode(msg);
                        if (decoded != null) {
                            suppress = Boolean.TRUE.equals(onWorldEvent.apply(player, decoded));
                        }
                    } catch (Exception ex) {
                        LOGGER.warning("[CDisc] Error while inspecting outgoing packet: " + ex);
                    }

                    if (suppress) {
                        promise.trySuccess();
                        return;
                    }
                    super.write(ctx, msg, promise);
                }
            });
        });
    }

    private WorldEventPacket tryDecode(Object packet) {
        if (packet == null) return null;

        PacketShape s = shape;
        if (s == null) {
            Class<?> klass = packet.getClass();
            if (nonMatchingClasses.contains(klass)) return null;

            s = resolveShape(klass);
            if (s == null) {
                nonMatchingClasses.add(klass);
                return null;
            }
            shape = s;
        } else if (s.packetClass != packet.getClass()) {
            return null;
        }

        try {
            int effectId = s.effectIdField.getInt(packet);
            int data = s.dataField.getInt(packet);
            Object pos = s.posField.get(packet);
            int x = s.posX.getInt(pos);
            int y = s.posY.getInt(pos);
            int z = s.posZ.getInt(pos);
            return new WorldEventPacket(effectId, x, y, z, data);
        } catch (Exception e) {
            return null;
        }
    }

    private static PacketShape resolveShape(Class<?> candidate) {
        String name = candidate.getSimpleName();
        if (!name.contains("WorldEvent") && !name.contains("LevelEvent")) {
            return null;
        }

        List<Field> intFields = new ArrayList<>();
        Field posCandidate = null;
        Field[] posFields = null;

        for (Field f : allFields(candidate)) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);

            if (f.getType() == int.class) {
                intFields.add(f);
            } else if (!f.getType().isPrimitive() && !f.getType().isArray()) {
                Field[] coords = findThreeIntFields(f.getType());
                if (coords != null) {
                    posCandidate = f;
                    posFields = coords;
                }
            }
        }

        if (intFields.size() < 2 || posCandidate == null) return null;

        Field effectIdField = intFields.get(0);
        Field dataField = intFields.get(intFields.size() - 1);

        return new PacketShape(candidate, effectIdField, dataField, posCandidate,
                posFields[0], posFields[1], posFields[2]);
    }

    private static Field[] findThreeIntFields(Class<?> type) {
        if (type == Object.class || type.isPrimitive()) return null;
        List<Field> ints = new ArrayList<>();
        for (Field f : allFields(type)) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == int.class) {
                f.setAccessible(true);
                ints.add(f);
            }
        }
        return ints.size() == 3 ? ints.toArray(new Field[0]) : null;
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

    private record PacketShape(Class<?> packetClass, Field effectIdField, Field dataField,
                                Field posField, Field posX, Field posY, Field posZ) {}
}
