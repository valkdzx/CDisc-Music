package dev.valkdz.cdisc.voice;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

public interface VoiceBackend {

    String name();

    VoiceSession createSession(Block block, World world, float distance);

    VoiceSession createEntitySession(Entity anchor, float distance);

    default VoiceSession createSyncedEntitySession(Entity anchor, float distance) {
        return createEntitySession(anchor, distance);
    }

    default boolean wantsPcm() {
        return false;
    }

    default boolean serves(java.util.UUID player) {
        return true;
    }
}
