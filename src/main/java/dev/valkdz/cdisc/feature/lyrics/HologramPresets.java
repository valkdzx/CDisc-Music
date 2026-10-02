package dev.valkdz.cdisc.feature.lyrics;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Json;

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
    private volatile Assignment fallback;

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
        fallback = null;
        if (!file.isFile()) return;

        try {
            Json root = Json.parse(Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8));
            HologramStyle base = HologramStyle.fromConfig(plugin.cdiscConfig());

            Iterator<Map.Entry<String, Json>> fields = root.path("presets").fields();
            while (fields.hasNext()) {
                Map.Entry<String, Json> entry = fields.next();
                try {
                    presets.put(UUID.fromString(entry.getKey()),
                            HologramStyle.fromJson(entry.getValue(), base));
                } catch (IllegalArgumentException ignored) {

                }
            }

            Iterator<Map.Entry<String, Json>> named = root.path("server").fields();
            while (named.hasNext()) {
                Map.Entry<String, Json> entry = named.next();
                if (validName(entry.getKey())) {
                    server.put(entry.getKey(), HologramStyle.fromJson(entry.getValue(), base));
                }
            }

            Iterator<Map.Entry<String, Json>> given = root.path("assigned").fields();
            while (given.hasNext()) {
                Map.Entry<String, Json> entry = given.next();
                String preset = entry.getValue().path("preset").asText("");
                if (!server.containsKey(preset)) continue;
                try {
                    assigned.put(UUID.fromString(entry.getKey()), new Assignment(preset,
                            entry.getValue().path("forced").asBoolean(false)));
                } catch (IllegalArgumentException ignored) {

                }
            }

            Json everyone = root.path("default");
            String preset = everyone.path("preset").asText("");
            if (server.containsKey(preset)) {
                fallback = new Assignment(preset, everyone.path("forced").asBoolean(false));
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
        HologramStyle given = styleOf(assigned.get(player));
        if (given != null) return given;

        Assignment everyone = fallback;
        HologramStyle common = styleOf(everyone);
        if (common != null && everyone.forced()) return common;

        HologramStyle own = presets.get(player);
        if (own != null) return own;
        return common != null ? common : defaults;
    }

    private HologramStyle styleOf(Assignment assignment) {
        return assignment == null ? null : server.get(assignment.preset());
    }

    public boolean coversOwn(UUID player) {
        Assignment given = assigned.get(player);
        return given != null && !given.forced() && server.containsKey(given.preset());
    }

    public boolean isForced(UUID player) {
        Assignment given = assignmentOf(player);
        return given != null && given.forced() && server.containsKey(given.preset());
    }

    public Assignment assignmentOf(UUID player) {
        Assignment given = assigned.get(player);
        return given != null ? given : fallback;
    }

    public boolean follows(UUID player, String name) {
        Assignment given = assignmentOf(player);
        return given != null && given.preset().equals(name);
    }

    public Assignment fallback() {
        return fallback;
    }

    public void setFallback(Assignment everyone) {
        fallback = everyone;
        saveLater();
    }

    public static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean validName(String name) {
        return name.matches("[a-z0-9_-]{1,32}") && !name.equals("none");
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

        List<UUID> freed = new ArrayList<>();
        assigned.forEach((id, given) -> {
            if (given.preset().equals(name)) freed.add(id);
        });
        freed.forEach(assigned::remove);
        Assignment everyone = fallback;
        if (everyone != null && everyone.preset().equals(name)) fallback = null;
        saveLater();
        return freed;
    }

    public void assign(UUID player, String name, boolean forced) {
        assigned.put(player, new Assignment(name, forced));
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
            Json root = Json.object();
            Json all = root.putObject("presets");
            for (Map.Entry<UUID, HologramStyle> entry : presets.entrySet()) {
                all.set(entry.getKey().toString(), entry.getValue().toJson());
            }
            Json named = root.putObject("server");
            for (Map.Entry<String, HologramStyle> entry : server.entrySet()) {
                named.set(entry.getKey(), entry.getValue().toJson());
            }
            Json given = root.putObject("assigned");
            for (Map.Entry<UUID, Assignment> entry : assigned.entrySet()) {
                Json one = given.putObject(entry.getKey().toString());
                one.put("preset", entry.getValue().preset());
                one.put("forced", entry.getValue().forced());
            }
            Assignment everyone = fallback;
            if (everyone != null) {
                Json one = root.putObject("default");
                one.put("preset", everyone.preset());
                one.put("forced", everyone.forced());
            }

            try {
                File folder = file.getParentFile();
                if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
                    plugin.getLogger().warning("Couldn't create " + folder + " for " + FILE_NAME);
                    return;
                }

                Path target = file.toPath();
                Path temp = target.resolveSibling(FILE_NAME + ".tmp");
                Files.write(temp, root.toPrettyString()
                        .getBytes(StandardCharsets.UTF_8));
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't write " + FILE_NAME + ": " + e.getMessage());
            }
        }
    }
}
