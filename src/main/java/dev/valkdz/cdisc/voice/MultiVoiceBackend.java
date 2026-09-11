package dev.valkdz.cdisc.voice;

import dev.valkdz.cdisc.speaker.SpeakerSettings;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class MultiVoiceBackend implements VoiceBackend {

    @FunctionalInterface
    private interface SessionFactory {
        VoiceSession open(VoiceBackend backend, float distance, boolean synced);
    }

    private static final long ASSIGNMENT_INTERVAL_TICKS = 20L;

    private final List<VoiceBackend> delegates = new CopyOnWriteArrayList<>();

    private final Set<MultiSession> live = ConcurrentHashMap.newKeySet();

    private final Map<VoiceBackend, Set<UUID>> exclusions = new ConcurrentHashMap<>();

    public MultiVoiceBackend(VoiceBackend... initial) {
        for (VoiceBackend backend : initial) {
            addBackend(backend);
        }
    }

    public void startListenerAssignment(org.bukkit.plugin.Plugin plugin) {
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAssignment,
                ASSIGNMENT_INTERVAL_TICKS, ASSIGNMENT_INTERVAL_TICKS);
    }

    private void refreshAssignment() {
        List<VoiceBackend> order = delegates;
        if (order.size() < 2) {

            if (!exclusions.isEmpty()) {
                exclusions.clear();
                order.forEach(backend -> publish(backend, Set.of()));
            }
            return;
        }

        Map<VoiceBackend, Set<UUID>> next = new HashMap<>();
        for (VoiceBackend backend : order) {
            next.put(backend, new HashSet<>());
        }

        for (org.bukkit.entity.Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean assigned = false;
            for (VoiceBackend backend : order) {
                boolean reachable;
                try {
                    reachable = backend.serves(id);
                } catch (Exception e) {
                    reachable = false;
                }
                if (!reachable) continue;

                if (assigned) {
                    next.get(backend).add(id);
                } else {
                    assigned = true;
                }
            }
        }

        next.forEach((backend, skip) -> {
            Set<UUID> current = exclusions.get(backend);
            if (skip.equals(current)) return;

            Set<UUID> frozen = Set.copyOf(skip);
            exclusions.put(backend, frozen);
            publish(backend, frozen);
        });
    }

    private void publish(VoiceBackend backend, Set<UUID> skip) {
        for (MultiSession session : live) {
            session.exclude(backend, skip);
        }
    }

    private Set<UUID> exclusionsFor(VoiceBackend backend) {
        return exclusions.getOrDefault(backend, Set.of());
    }

    public void addBackend(VoiceBackend backend) {
        if (backend == null || delegates.contains(backend)) return;

        if (!backend.wantsPcm()) {
            dev.valkdz.cdisc.Main.getInstance().getLogger().severe(
                    backend.name() + " expects encoded audio and cannot share a decode with "
                            + "the other voice backends. It will not receive music.");
            return;
        }

        delegates.add(backend);
        for (MultiSession session : live) {
            session.attach(backend);
        }
    }

    @Override
    public String name() {
        if (delegates.isEmpty()) return "no voice backend";
        return delegates.stream().map(VoiceBackend::name).collect(Collectors.joining(" + "));
    }

    @Override
    public boolean wantsPcm() {
        return true;
    }

    @Override
    public VoiceSession createSession(Block block, World world, float distance) {
        return open(distance, false,
                (backend, dist, synced) -> backend.createSession(block, world, dist));
    }

    @Override
    public VoiceSession createEntitySession(Entity anchor, float distance) {
        return open(distance, false, (backend, dist, synced) -> synced
                ? backend.createSyncedEntitySession(anchor, dist)
                : backend.createEntitySession(anchor, dist));
    }

    @Override
    public VoiceSession createSyncedEntitySession(Entity anchor, float distance) {
        return open(distance, true, (backend, dist, synced) -> synced
                ? backend.createSyncedEntitySession(anchor, dist)
                : backend.createEntitySession(anchor, dist));
    }

    private VoiceSession open(float distance, boolean synced, SessionFactory factory) {
        MultiSession session = new MultiSession(this, factory, distance, synced);
        for (VoiceBackend backend : delegates) {
            session.attach(backend);
        }
        if (session.isEmpty()) return null;

        live.add(session);
        return session;
    }

    private void forget(MultiSession session) {
        live.remove(session);
    }

    private static final class MultiSession implements VoiceSession {

        private final MultiVoiceBackend owner;
        private final SessionFactory factory;

        private final Map<VoiceBackend, VoiceSession> parts = new ConcurrentHashMap<>();

        private volatile float distance;
        private volatile boolean synced;
        private volatile UUID privateListener;
        private volatile int directVolume = -1;
        private volatile SpeakerSettings settings;

        private volatile boolean closed;

        MultiSession(MultiVoiceBackend owner, SessionFactory factory, float distance, boolean synced) {
            this.owner = owner;
            this.factory = factory;
            this.distance = distance;
            this.synced = synced;
        }

        void attach(VoiceBackend backend) {
            if (closed || parts.containsKey(backend)) return;

            VoiceSession part;
            try {
                part = factory.open(backend, distance, synced);
            } catch (Exception e) {
                warn(backend.name() + " failed to open a jukebox source: " + e);
                return;
            }
            if (part == null) return;

            SpeakerSettings current = settings;
            if (current != null) part.applySpeakerSettings(current);
            if (synced) part.setSynced(true);
            if (directVolume >= 0) part.setDirectVolume(directVolume);

            // Before the carrier, so a carrier this backend must skip is recognised as one
            // rather than given a direct feed first.
            Set<UUID> skip = owner.exclusionsFor(backend);
            if (!skip.isEmpty()) part.setExcludedListeners(skip);
            UUID carrier = privateListener;
            if (carrier != null) part.setPrivateListener(carrier);

            parts.put(backend, part);

            if (closed && parts.remove(backend) != null) {
                closeQuietly(part);
            }
        }

        boolean isEmpty() {
            return parts.isEmpty();
        }

        void exclude(VoiceBackend backend, Set<UUID> skip) {
            VoiceSession part = parts.get(backend);
            if (part == null) return;
            try {
                part.setExcludedListeners(skip);
            } catch (Exception e) {
                warn(backend.name() + " refused a listener exclusion: " + e);
            }
        }

        @Override
        public void setExcludedListeners(Set<UUID> excluded) {
        }

        @Override
        public void sendFrame(byte[] pcm) {
            if (closed) return;
            for (VoiceSession part : parts.values()) {
                try {
                    part.sendFrame(pcm);
                } catch (Exception ignored) {

                }
            }
        }

        @Override
        public boolean isClosed() {
            if (closed) return true;
            for (VoiceSession part : parts.values()) {
                if (!part.isClosed()) return false;
            }
            return true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            owner.forget(this);
            List<VoiceSession> open = new ArrayList<>(parts.values());
            parts.clear();
            open.forEach(MultiSession::closeQuietly);
        }

        @Override
        public void setDistance(float distance) {
            this.distance = distance;
            for (VoiceSession part : parts.values()) {
                part.setDistance(distance);
            }
        }

        @Override
        public void setPrivateListener(UUID listener) {
            if (Objects.equals(listener, privateListener)) return;
            this.privateListener = listener;
            for (VoiceSession part : parts.values()) {
                part.setPrivateListener(listener);
            }
        }

        @Override
        public void setSynced(boolean synced) {
            if (!synced || this.synced) return;
            this.synced = true;
            for (VoiceSession part : parts.values()) {
                part.setSynced(true);
            }
        }

        @Override
        public void setDirectVolume(int volume) {
            this.directVolume = volume;
            for (VoiceSession part : parts.values()) {
                part.setDirectVolume(volume);
            }
        }

        @Override
        public void applySpeakerSettings(SpeakerSettings settings) {
            if (settings == null) return;
            this.settings = settings;
            for (VoiceSession part : parts.values()) {
                part.applySpeakerSettings(settings);
            }
        }

        private static void closeQuietly(VoiceSession part) {
            try {
                part.close();
            } catch (Exception ignored) {
            }
        }

        private static void warn(String message) {
            dev.valkdz.cdisc.Main.getInstance().getLogger().warning(message);
        }
    }
}
