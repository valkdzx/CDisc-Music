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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HologramPresets {

    public static final String FILE_NAME = "holograms.json";

    private final Main plugin;
    private final File file;

    public record Assignment(String preset, boolean forced) {
    }

    private final Map<UUID, HologramStyle> presets = new ConcurrentHashMap<>();
    private final Map<String, HologramStyle> server = new ConcurrentHashMap<>();
    private final Map<UUID, Assignment> assigned = new ConcurrentHashMap<>();

    private final Object saveLock = new Object();

    public HologramPresets(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        load();
    }

    public void load() {
        presets.clear();
        server.clear();
        assigned.clear();
        if (!file.isFile()) return;

        try {
            JsonNode root = HologramStyle.mapper().readTree(file);
            HologramStyle base = HologramStyle.fromConfig(plugin.cdiscConfig());

            Iterator<Map.Entry<String, JsonNode>> fields = root.path("presets").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                try {
                    presets.put(UUID.fromString(entry.getKey()),
                            HologramStyle.fromJson(entry.getValue(), base));
                } catch (IllegalArgumentException ignored) {

                }
            }

            Iterator<Map.Entry<String, JsonNode>> named = root.path("server").fields();
            while (named.hasNext()) {
                Map.Entry<String, JsonNode> entry = named.next();
                if (validName(entry.getKey())) {
                    server.put(entry.getKey(), HologramStyle.fromJson(entry.getValue(), base));
                }
            }

            Iterator<Map.Entry<String, JsonNode>> given = root.path("assigned").fields();
            while (given.hasNext()) {
                Map.Entry<String, JsonNode> entry = given.next();
                String preset = entry.getValue().path("preset").asText("");
                if (!server.containsKey(preset)) continue;
                try {
                    assigned.put(UUID.fromString(entry.getKey()), new Assignment(preset,
                            entry.getValue().path("forced").asBoolean(false)));
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
        Assignment given = assigned.get(player);
        HologramStyle theirs = given == null ? null : server.get(given.preset());
        if (theirs != null && given.forced()) return theirs;

        HologramStyle own = presets.get(player);
        if (own != null) return own;
        return theirs != null ? theirs : defaults;
    }

    public boolean isForced(UUID player) {
        Assignment given = assigned.get(player);
        return given != null && given.forced() && server.containsKey(given.preset());
    }

    public static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean validName(String name) {
        return name.matches("[a-z0-9_-]{1,32}");
    }

    public HologramStyle server(String name) {
        return server.get(name);
    }

    public List<String> serverNames() {
        return server.keySet().stream().sorted().toList();
    }

    public void setServer(String name, HologramStyle style) {
        server.put(name, style);
        saveLater();
    }

    public List<UUID> removeServer(String name) {
        if (server.remove(name) == null) return List.of();

        List<UUID> freed = holdersOf(name);
        freed.forEach(assigned::remove);
        saveLater();
        return freed;
    }

    public List<UUID> holdersOf(String name) {
        List<UUID> holders = new ArrayList<>();
        assigned.forEach((id, given) -> {
            if (given.preset().equals(name)) holders.add(id);
        });
        return holders;
    }

    public void assign(UUID player, String name, boolean forced) {
        assigned.put(player, new Assignment(name, forced));
        if (!forced) presets.remove(player);
        saveLater();
    }

    public boolean unassign(UUID player) {
        if (assigned.remove(player) == null) return false;
        saveLater();
        return true;
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

    public int serverSize() {
        return server.size();
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
        dev.valkdz.cdisc.util.Tasks.async(plugin, this::saveNow);
    }

    public void saveNow() {
        synchronized (saveLock) {
            ObjectNode root = HologramStyle.mapper().createObjectNode();
            ObjectNode all = root.putObject("presets");
            for (Map.Entry<UUID, HologramStyle> entry : presets.entrySet()) {
                all.set(entry.getKey().toString(), entry.getValue().toJson());
            }
            ObjectNode named = root.putObject("server");
            for (Map.Entry<String, HologramStyle> entry : server.entrySet()) {
                named.set(entry.getKey(), entry.getValue().toJson());
            }
            ObjectNode given = root.putObject("assigned");
            for (Map.Entry<UUID, Assignment> entry : assigned.entrySet()) {
                ObjectNode one = given.putObject(entry.getKey().toString());
                one.put("preset", entry.getValue().preset());
                one.put("forced", entry.getValue().forced());
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
