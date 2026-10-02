package dev.valkdz.cdisc.api;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.PlaybackManager;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;

public final class CDiscApi {

    private CDiscApi() {
    }

    public static Optional<NowPlaying> nowPlaying(Block jukebox) {
        PlaybackManager manager = manager();
        if (manager == null || jukebox == null) return Optional.empty();
        return Optional.ofNullable(manager.nowPlaying(jukebox));
    }

    public static Optional<NowPlaying> audibleTo(Player player) {
        PlaybackManager manager = manager();
        if (manager == null || player == null) return Optional.empty();
        Block nearest = manager.nearestAudible(player.getLocation());
        return nearest == null ? Optional.empty() : Optional.ofNullable(manager.nowPlaying(nearest));
    }

    public static Set<Block> playingJukeboxes() {
        PlaybackManager manager = manager();
        return manager == null ? Set.of() : Set.copyOf(manager.activeBlocks());
    }

    private static PlaybackManager manager() {
        Main plugin = Main.getInstance();
        return plugin == null || !plugin.isEnabled() ? null : plugin.getAudioPlayerManager();
    }
}
