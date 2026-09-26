package dev.valkdz.cdisc.lyrics.chat;

import java.util.ArrayDeque;
import java.util.List;

public abstract class ChatFeed {

    static final int KEPT = 32;

    public record Snapshot(List<ChatMessage> messages, long count) {

        public int index() {
            return (int) Math.min(Integer.MAX_VALUE, count - 1);
        }
    }

    private final ArrayDeque<ChatMessage> recent = new ArrayDeque<>();
    private long count;
    private Snapshot snapshot = new Snapshot(List.of(), 0);

    volatile long wantedAt = System.currentTimeMillis();

    volatile boolean closed;

    abstract void open();

    abstract void shut();

    final void close() {
        closed = true;
        shut();
    }

    protected final synchronized void add(ChatMessage message) {
        if (message == null) return;

        if (recent.size() == KEPT) recent.removeFirst();
        recent.addLast(message);
        count++;
    }

    final synchronized Snapshot snapshot() {
        if (snapshot.count() != count) snapshot = new Snapshot(List.copyOf(recent), count);
        return snapshot;
    }

    static long backoffMs(int failures) {
        return Math.min(60_000L, 5_000L << Math.min(4, Math.max(0, failures)));
    }
}
