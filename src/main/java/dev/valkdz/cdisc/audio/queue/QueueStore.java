package dev.valkdz.cdisc.audio.queue;

import dev.valkdz.cdisc.Main;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class QueueStore {

    private static final String FILE_NAME = "queues.yml";

    private static final int FORMAT = 1;

    private final Main plugin;

    private final Map<String, Saved> pending = new ConcurrentHashMap<>();

    private record Saved(String world, int x, int y, int z, String data) {
    }

    public QueueStore(Main plugin) {
        this.plugin = plugin;
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
            pending.put(world + ";" + x + ";" + y + ";" + z, new Saved(world, x, y, z, data));
            count++;
            discs += entry.getInt("discs", 0);
        }

        if (count > 0) {
            plugin.getLogger().info("Loaded " + discs + " queued disc(s) across " + count + " jukebox(es).");
        }
    }

    public DiscQueue take(Block block) {
        Saved saved = pending.remove(keyOf(block));
        if (saved == null) return null;

        DiscQueue queue = decode(saved.data());
        if (queue == null) {
            plugin.getLogger().warning("[CDisc] Dropping an unreadable saved queue at " + keyOf(block) + ".");
        }
        return queue;
    }

    public void forget(Block block) {
        pending.remove(keyOf(block));
    }

    public void save(Map<Block, DiscQueue> live, boolean async) {
        YamlConfiguration cfg = new YamlConfiguration();
        int count = 0;

        for (Map.Entry<Block, DiscQueue> entry : live.entrySet()) {
            DiscQueue queue = entry.getValue();
            if (queue.isEmpty()) continue;

            String encoded = encode(queue);
            if (encoded == null) continue;

            Block block = entry.getKey();
            String path = "queues." + count++;
            cfg.set(path + ".world", block.getWorld().getName());
            cfg.set(path + ".x", block.getX());
            cfg.set(path + ".y", block.getY());
            cfg.set(path + ".z", block.getZ());
            cfg.set(path + ".discs", queue.filledCount());
            cfg.set(path + ".data", encoded);
        }

        for (Saved saved : pending.values()) {
            String path = "queues." + count++;
            cfg.set(path + ".world", saved.world());
            cfg.set(path + ".x", saved.x());
            cfg.set(path + ".y", saved.y());
            cfg.set(path + ".z", saved.z());
            cfg.set(path + ".data", saved.data());
        }

        String text = cfg.saveToString();
        int written = count;
        if (async && plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(text, written));
        } else {
            write(text, written);
        }
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

    private String encode(DiscQueue queue) {
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
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private DiscQueue decode(String encoded) {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(encoded));
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
