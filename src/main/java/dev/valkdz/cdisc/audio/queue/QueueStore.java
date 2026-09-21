package dev.valkdz.cdisc.audio.queue;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class QueueStore {

    private static final String FILE_NAME = "queues.yml";

    private static final int FORMAT = 1;

    private final Main plugin;

    private final NamespacedKey queueKey;

    private final Map<String, Saved> pending = new ConcurrentHashMap<>();

    private record Saved(String world, int x, int y, int z, int discs, String data) {
    }

    public QueueStore(Main plugin) {
        this.plugin = plugin;
        this.queueKey = new NamespacedKey(plugin, "cdisc_queue");
    }

    private static String keyOf(Block block) {
        return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("queues");
        if (root == null) return;

        int count = 0;
        int discs = 0;
        for (String id : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(id);
            if (entry == null) continue;

            String world = entry.getString("world");
            String data = entry.getString("data");
            if (world == null || data == null) continue;

            int x = entry.getInt("x");
            int y = entry.getInt("y");
            int z = entry.getInt("z");
            int filled = entry.getInt("discs", 0);
            pending.put(world + ";" + x + ";" + y + ";" + z, new Saved(world, x, y, z, filled, data));
            count++;
            discs += filled;
        }

        if (count > 0) {
            plugin.getLogger().info("Loaded " + discs + " queued disc(s) across " + count
                    + " jukebox(es); they move into the jukeboxes as their chunks load.");
        }
    }

    public DiscQueue take(Block block) {
        Saved saved = pending.remove(keyOf(block));
        if (saved == null) return null;

        DiscQueue queue = decode(bytesOf(saved.data()));
        if (queue == null) {
            plugin.getLogger().warning("[CDisc] Dropping an unreadable saved queue at " + keyOf(block) + ".");
        }
        return queue;
    }

    public DiscQueue readBlock(Block block) {
        if (!loaded(block)) return null;
        if (!(block.getState() instanceof Jukebox jukebox)) return null;

        byte[] data = jukebox.getPersistentDataContainer().get(queueKey, PersistentDataType.BYTE_ARRAY);
        if (data == null) return null;

        DiscQueue queue = decode(data);
        if (queue == null) {
            plugin.getLogger().warning("[CDisc] Dropping an unreadable queue kept in the jukebox at "
                    + keyOf(block) + ".");
        }
        return queue;
    }

    public boolean clearBlock(Block block) {
        if (!loaded(block)) return false;
        if (!(block.getState() instanceof Jukebox jukebox)) return true;

        PersistentDataContainer pdc = jukebox.getPersistentDataContainer();
        if (!pdc.has(queueKey, PersistentDataType.BYTE_ARRAY)) return true;

        pdc.remove(queueKey);
        jukebox.update(true, false);
        return true;
    }

    public void forget(Block block) {
        pending.remove(keyOf(block));
    }

    public boolean migratePending() {
        if (pending.isEmpty()) return false;

        boolean moved = false;
        for (Map.Entry<String, Saved> entry : pending.entrySet()) {
            Saved saved = entry.getValue();
            World world = Bukkit.getWorld(saved.world());
            if (world == null) continue;
            if (!world.isChunkLoaded(saved.x() >> 4, saved.z() >> 4)) continue;

            byte[] data = bytesOf(saved.data());
            if (data == null) continue;
            if (!writeBlock(world.getBlockAt(saved.x(), saved.y(), saved.z()), data)) continue;

            pending.remove(entry.getKey());
            moved = true;
        }
        return moved;
    }

    public void save(Map<Block, DiscQueue> live, boolean async) {
        YamlConfiguration cfg = new YamlConfiguration();
        int count = 0;

        for (Map.Entry<Block, DiscQueue> entry : live.entrySet()) {
            Block block = entry.getKey();
            DiscQueue queue = entry.getValue();

            if (queue.isEmpty()) {
                clearBlock(block);
                continue;
            }

            byte[] encoded = encode(queue);
            if (encoded == null) continue;

            // The jukebox is saved with its chunk, so a crash rolls its queue back exactly as
            // far as it rolls back the inventories the discs came from. The file cannot do that.
            if (writeBlock(block, encoded)) continue;

            String path = "queues." + count++;
            cfg.set(path + ".world", block.getWorld().getName());
            cfg.set(path + ".x", block.getX());
            cfg.set(path + ".y", block.getY());
            cfg.set(path + ".z", block.getZ());
            cfg.set(path + ".discs", queue.filledCount());
            cfg.set(path + ".data", Base64.getEncoder().encodeToString(encoded));
        }

        for (Saved saved : pending.values()) {
            String path = "queues." + count++;
            cfg.set(path + ".world", saved.world());
            cfg.set(path + ".x", saved.x());
            cfg.set(path + ".y", saved.y());
            cfg.set(path + ".z", saved.z());
            cfg.set(path + ".discs", saved.discs());
            cfg.set(path + ".data", saved.data());
        }

        String text = cfg.saveToString();
        int written = count;
        if (async && plugin.isEnabled()) {
            Tasks.async(plugin, () -> write(text, written));
        } else {
            write(text, written);
        }
    }

    private boolean writeBlock(Block block, byte[] encoded) {
        if (!loaded(block)) return false;
        if (!(block.getState() instanceof Jukebox jukebox)) return false;

        PersistentDataContainer pdc = jukebox.getPersistentDataContainer();
        if (Arrays.equals(encoded, pdc.get(queueKey, PersistentDataType.BYTE_ARRAY))) return true;

        pdc.set(queueKey, PersistentDataType.BYTE_ARRAY, encoded);
        jukebox.update(true, false);
        return true;
    }

    private static boolean loaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private void write(String text, int count) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (count == 0) {
            if (file.exists() && !file.delete()) {
                plugin.getLogger().warning("[CDisc] Couldn't clear " + FILE_NAME + ".");
            }
            return;
        }

        try {
            File folder = file.getParentFile();
            if (folder != null) folder.mkdirs();

            File temp = new File(file.getParentFile(), FILE_NAME + ".tmp");
            Files.write(temp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().warning("[CDisc] Couldn't save the jukebox queues: " + e.getMessage());
        }
    }

    private byte[] encode(DiscQueue queue) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DeflaterOutputStream deflated = new DeflaterOutputStream(bytes);
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(deflated)) {

            out.writeInt(FORMAT);
            out.writeInt(queue.getCurrentIndex());
            out.writeUTF(queue.getPolicy().name());
            out.writeInt(queue.filledCount());
            for (int i = 0; i < DiscQueue.CAPACITY; i++) {
                ItemStack disc = queue.getSlot(i);
                if (disc == null) continue;
                out.writeInt(i);
                out.writeObject(disc);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CDisc] Couldn't encode a jukebox queue: " + e.getMessage());
            return null;
        }
        return bytes.toByteArray();
    }

    private static byte[] bytesOf(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private DiscQueue decode(byte[] encoded) {
        if (encoded == null) return null;

        try (ByteArrayInputStream bytes = new ByteArrayInputStream(encoded);
             InflaterInputStream inflated = new InflaterInputStream(bytes);
             BukkitObjectInputStream in = new BukkitObjectInputStream(inflated)) {

            if (in.readInt() != FORMAT) return null;

            DiscQueue queue = new DiscQueue();
            int current = in.readInt();
            try {
                queue.setPolicy(PlayedPolicy.valueOf(in.readUTF()));
            } catch (IllegalArgumentException ignored) {

            }

            int filled = in.readInt();
            for (int n = 0; n < filled; n++) {
                int slot = in.readInt();
                Object read = in.readObject();
                if (read instanceof ItemStack disc) queue.setSlot(slot, disc);
            }

            queue.setCurrentIndex(queue.getSlot(current) == null ? -1 : current);
            return queue;
        } catch (Exception e) {
            plugin.getLogger().warning("[CDisc] Couldn't read a saved jukebox queue: " + e.getMessage());
            return null;
        }
    }
}
