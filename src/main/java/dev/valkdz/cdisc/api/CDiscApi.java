package dev.valkdz.cdisc.api;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class CDiscApi {

    private CDiscApi() {
    }

    public static boolean isEnabled() {
        Main plugin = Main.getInstance();
        return plugin != null && plugin.isEnabled();
    }

    public static boolean isVoiceReady() {
        PlaybackManager manager = manager();
        return manager != null && manager.hasVoiceBackend();
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

    public static JukeboxControl jukebox(Block jukebox) {
        Main plugin = enabled();
        if (jukebox == null || jukebox.getType() != Material.JUKEBOX) {
            throw new IllegalArgumentException("not a jukebox: " + jukebox);
        }
        return new ApiJukebox(plugin, jukebox);
    }

    public static QueueControl queue(Block jukebox) {
        return jukebox(jukebox).queue();
    }

    public static GuiControl gui() {
        return new ApiGui(enabled());
    }

    public static PlayerSettings player(Player player) {
        return new ApiPlayer(enabled(), Objects.requireNonNull(player, "player"));
    }

    public static CompletableFuture<ItemStack> createDisc(String source) {
        return createDisc(source, null, null);
    }

    public static CompletableFuture<ItemStack> createDisc(String source, String title, String author) {
        Main plugin = enabled();
        return ApiDiscs.resolve(source, title, author, 1)
                .thenApply(tracks -> ApiDiscs.item(tracks.get(0), ApiDiscs.material(plugin), false));
    }

    public static Optional<DiscInfo> readDisc(ItemStack item) {
        return ApiDiscs.read(item);
    }

    public static List<String> localTracks() {
        Main plugin = Main.getInstance();
        if (plugin == null || !plugin.isEnabled() || !plugin.getLocalMusic().isEnabled()) return List.of();
        return List.copyOf(plugin.getLocalMusic().index());
    }

    public static void reload() {
        Main plugin = enabled();
        Tasks.global(plugin, plugin::reloadEverything);
    }

    private static Main enabled() {
        Main plugin = Main.getInstance();
        if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("CDisc is not enabled");
        return plugin;
    }

    static PlaybackManager manager() {
        Main plugin = Main.getInstance();
        return plugin == null || !plugin.isEnabled() ? null : plugin.getAudioPlayerManager();
    }
}
