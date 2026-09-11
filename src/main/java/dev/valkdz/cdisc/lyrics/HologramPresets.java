package dev.valkdz.cdisc.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.valkdz.cdisc.Main;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HologramPresets {

    public static final String FILE_NAME = "holograms.json";

    private final Main plugin;
    private final File file;

    private final Map<UUID, HologramStyle> presets = new ConcurrentHashMap<>();

    private final Object saveLock = new Object();

    public HologramPresets(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        load();
    }

    public void load() {
        presets.clear();
        if (!file.isFile()) return;

        try {
            JsonNode root = HologramStyle.mapper().readTree(file);
            JsonNode all = root.path("presets");
            if (!all.isObject()) return;

            HologramStyle base = HologramStyle.fromConfig(plugin.cdiscConfig());

            Iterator<Map.Entry<String, JsonNode>> fields = all.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                try {
                    presets.put(UUID.fromString(entry.getKey()),
                            HologramStyle.fromJson(entry.getValue(), base));
                } catch (IllegalArgumentException ignored) {

                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't read " + FILE_NAME + ": " + e.getMessage()
                    + ". Presets are starting empty; the file is left as it is.");
        }
    }

    public HologramStyle get(UUID player) {
        return presets.get(player);
    }

    public boolean has(UUID player) {
        return presets.containsKey(player);
    }

    public HologramStyle getOrDefault(UUID player) {
        return orDefault(player, HologramStyle.fromConfig(plugin.cdiscConfig()));
    }

    public HologramStyle orDefault(UUID player, HologramStyle defaults) {
        HologramStyle own = presets.get(player);
        return own != null ? own : defaults;
    }

    public void set(UUID player, HologramStyle style) {
        presets.put(player, style);
        saveLater();
    }

    public void clear(UUID player) {
        if (presets.remove(player) != null) saveLater();
    }

    public int size() {
        return presets.size();
    }

    public boolean copy(UUID from, UUID to) {
        HologramStyle theirs = presets.get(from);
        if (theirs == null) return false;

        set(to, theirs);
        return true;
    }

    private void saveLater() {
        if (!plugin.isEnabled()) {
            saveNow();
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::saveNow);
    }

    public void saveNow() {
        synchronized (saveLock) {
            ObjectNode root = HologramStyle.mapper().createObjectNode();
            ObjectNode all = root.putObject("presets");
            for (Map.Entry<UUID, HologramStyle> entry : presets.entrySet()) {
                all.set(entry.getKey().toString(), entry.getValue().toJson());
            }

            try {
                File folder = file.getParentFile();
                if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
                    plugin.getLogger().warning("Couldn't create " + folder + " for " + FILE_NAME);
                    return;
                }

                Path target = file.toPath();
                Path temp = target.resolveSibling(FILE_NAME + ".tmp");
                Files.write(temp, HologramStyle.mapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(root)
                        .getBytes(StandardCharsets.UTF_8));
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't write " + FILE_NAME + ": " + e.getMessage());
            }
        }
    }
}
