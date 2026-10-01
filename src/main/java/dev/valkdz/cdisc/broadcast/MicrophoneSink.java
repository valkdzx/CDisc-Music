package dev.valkdz.cdisc.broadcast;

import java.util.UUID;

public interface MicrophoneSink {

    boolean wants(UUID player, int distance);

    void feed(UUID player, byte[] pcm, int distance);

    void ended(UUID player);

    default void onForget(java.util.function.Consumer<UUID> forget) {
    }
}
