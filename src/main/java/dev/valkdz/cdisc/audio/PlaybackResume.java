package dev.valkdz.cdisc.audio;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.queue.RepeatMode;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PlaybackResume implements Listener {

    private static final String FILE_NAME = "playback.yml";

    private final Main plugin;

    private final Map<String, List<Entry>> pending = new ConcurrentHashMap<>();

    private record Entry(String world, int x, int y, int z, long positionMs,
                         RepeatMode repeat, boolean shuffle) {
        String chunkKey() {
            return world + ":" + (x >> 4) + ":" + (z >> 4);
        }

        boolean hasTrack() {
            return positionMs >= 0;
        }
    }

    public PlaybackResume(Main plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("resume-playback", true);
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) return;

        if (!isEnabled()) {
            file.delete();
            return;
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("jukeboxes");
        int count = 0;
        int playing = 0;

        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(key);
                if (entry == null) continue;

                String world = entry.getString("world");
                if (world == null) continue;

                Entry saved = new Entry(world,
                        entry.getInt("x"), entry.getInt("y"), entry.getInt("z"),
                        entry.getLong("position", 0L),
                        repeatOf(entry.getString("repeat")),
                        entry.getBoolean("shuffle", false));
                pending.computeIfAbsent(saved.chunkKey(), k -> new ArrayList<>()).add(saved);
                count++;
                if (saved.hasTrack()) playing++;
            }
        }

        file.delete();
        if (count > 0) {

            plugin.getLogger().info("Restoring " + count + " jukebox(es) as their "
                    + "chunks load, " + playing + " of them mid-track.");
        }
    }

    public void resumeLoadedChunks() {
        if (pending.isEmpty()) return;

        for (List<Entry> entries : List.copyOf(pending.values())) {
            for (Entry entry : List.copyOf(entries)) {
                World world = Bukkit.getWorld(entry.world());
                if (world == null) continue;
                if (!world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
                resume(entry);
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (pending.isEmpty()) return;

        Chunk chunk = event.getChunk();
        List<Entry> entries = pending.remove(
                chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ());
        if (entries == null) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Entry entry : entries) resume(entry);
        }, 20L);
    }

    private static RepeatMode repeatOf(String name) {
        if (name == null) return RepeatMode.OFF;
        try {
            return RepeatMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return RepeatMode.OFF;
        }
    }

    private void resume(Entry entry) {
        pending.computeIfPresent(entry.chunkKey(), (key, list) -> {
            list.remove(entry);
            return list.isEmpty() ? null : list;
        });

        World world = Bukkit.getWorld(entry.world());
        if (world == null) return;

        Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
        LavaPlayerManager apm = plugin.getAudioPlayerManager();

        apm.setRepeatMode(block, entry.repeat());
        apm.setShuffle(block, entry.shuffle());

        if (!entry.hasTrack()) return;
        if (!(block.getState() instanceof Jukebox jukebox) || !jukebox.hasRecord()) return;
        if (apm.hasActiveSession(block)) return;

        ItemStack record = jukebox.getRecord();
        ItemMeta meta = record.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String query = pdc.get(new NamespacedKey("cdisc", "cdisc_url"), PersistentDataType.STRING);
        if (query == null) return;

        String fallback = pdc.get(new NamespacedKey("cdisc", "cdisc_fallback_url"), PersistentDataType.STRING);
        String title = pdc.get(new NamespacedKey("cdisc", "cdisc_title"), PersistentDataType.STRING);
        String author = pdc.get(new NamespacedKey("cdisc", "cdisc_author"), PersistentDataType.STRING);
        String fetch = pdc.get(new NamespacedKey("cdisc", "music_fetch"), PersistentDataType.STRING);

        apm.seedQueue(block, record.clone(), true);
        apm.startPlaying(block, query, fallback, title, author, fetch, entry.positionMs());
    }

    public void save() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!isEnabled()) {
            if (file.exists()) file.delete();
            return;
        }

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        YamlConfiguration cfg = new YamlConfiguration();

        Map<Block, Long> positions = new LinkedHashMap<>();
        for (Block block : apm.activeBlocks()) {
            LavaPlayerManager.PlaybackInfo info = apm.getPlaybackInfo(block);
            if (info == null) continue;

            if (info.paused()) continue;

            positions.put(block, info.live() ? 0L : info.position());
        }

        Set<Block> blocks = new LinkedHashSet<>(positions.keySet());
        blocks.addAll(apm.blocksWithPlaybackModes());

        int count = 0;
        for (Block block : blocks) {
            RepeatMode repeat = apm.getRepeatMode(block);
            boolean shuffle = apm.isShuffle(block);
            Long position = positions.get(block);

            String key = "jukeboxes." + count++;
            cfg.set(key + ".world", block.getWorld().getName());
            cfg.set(key + ".x", block.getX());
            cfg.set(key + ".y", block.getY());
            cfg.set(key + ".z", block.getZ());

            cfg.set(key + ".position", position == null ? -1L : position);
            if (repeat != RepeatMode.OFF) cfg.set(key + ".repeat", repeat.name());
            if (shuffle) cfg.set(key + ".shuffle", true);
        }

        if (count == 0) {
            if (file.exists()) file.delete();
            return;
        }

        try {
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Couldn't save what was playing: " + e.getMessage());
        }
    }
}
