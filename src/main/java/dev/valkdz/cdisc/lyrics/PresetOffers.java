package dev.valkdz.cdisc.lyrics;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PresetOffers {

    private static final long LIFETIME_MS = 2 * 60 * 1000L;

    public record Offer(UUID from, String fromName, HologramStyle style, long madeAt) {

        public boolean expired() {
            return System.currentTimeMillis() - madeAt > LIFETIME_MS;
        }
    }

    private final Map<UUID, Offer> pending = new ConcurrentHashMap<>();

    public static long lifetimeSeconds() {
        return LIFETIME_MS / 1000L;
    }

    public boolean offer(UUID to, UUID from, String fromName, HologramStyle style) {
        Offer previous = pending.put(to,
                new Offer(from, fromName, style, System.currentTimeMillis()));
        return previous != null && !previous.expired();
    }

    public Offer peek(UUID to) {
        Offer waiting = pending.get(to);
        if (waiting == null) return null;

        if (waiting.expired()) {
            pending.remove(to, waiting);
            return null;
        }
        return waiting;
    }

    public Offer claim(UUID to) {
        Offer waiting = peek(to);
        if (waiting == null) return null;

        pending.remove(to, waiting);
        return waiting;
    }

    public void drop(UUID to) {
        pending.remove(to);
    }
}
