package dev.valkdz.cdisc.speaker;

import dev.valkdz.cdisc.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SpeakerGroupManager {

    private final Main plugin;
    private final File file;

    private final Map<UUID, SpeakerGroup> groups = new ConcurrentHashMap<>();

    private final Map<String, SpeakerGroup> byBlock = new ConcurrentHashMap<>();

    public SpeakerGroupManager(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "speakers.yml");
        load();
    }

    public SpeakerGroup groupAt(Block block) {
        return block == null ? null : byBlock.get(key(block.getLocation()));
    }

    public boolean isSpeaker(Block block) {
        SpeakerGroup group = groupAt(block);
        return group != null && !group.isMain(block.getLocation());
    }

    public boolean isMain(Block block) {
        SpeakerGroup group = groupAt(block);
        return group != null && group.isMain(block.getLocation());
    }

    public SpeakerGroup byId(UUID id) {
        return groups.get(id);
    }

    public boolean canManage(Player player, SpeakerGroup group) {
        if (group == null) return true;
        if (dev.valkdz.cdisc.permission.Perms.isAdmin(player)) return true;
        if (!plugin.cdiscConfig().isSpeakerProtectionEnabled()) return true;
        UUID owner = group.owner();
        return owner == null || owner.equals(player.getUniqueId());
    }

    public boolean canManageAt(Player player, Block block) {
        return canManage(player, groupAt(block));
    }

    public SpeakerGroup byName(String name) {
        for (SpeakerGroup group : groups.values()) {
            if (group.name().equalsIgnoreCase(name)) return group;
        }
        return null;
    }

    public Collection<SpeakerGroup> all() {
        return new ArrayList<>(groups.values());
    }

    public List<SpeakerGroup> ownedBy(UUID owner) {
        List<SpeakerGroup> result = new ArrayList<>();
        for (SpeakerGroup group : groups.values()) {
            if (owner.equals(group.owner())) result.add(group);
        }
        return result;
    }

    public SpeakerGroup create(String name, UUID owner, Block main) {
        SpeakerGroup group = new SpeakerGroup(
                UUID.randomUUID(), name, owner, main.getLocation());
        groups.put(group.id(), group);
        byBlock.put(key(main.getLocation()), group);
        save();
        return group;
    }

    public enum PairResult {
        OK,
        DISABLED,
        ALREADY_PAIRED,
        DIFFERENT_WORLD,
        TOO_FAR,
        GROUP_FULL
    }

    public PairResult canAdd(SpeakerGroup group, Block speaker) {
        if (!plugin.cdiscConfig().isSpeakerGroupEnabled()) return PairResult.DISABLED;
        if (groupAt(speaker) != null) return PairResult.ALREADY_PAIRED;

        Location main = group.main();
        if (main.getWorld() == null || !main.getWorld().equals(speaker.getWorld())) {
            return PairResult.DIFFERENT_WORLD;
        }

        int max = plugin.cdiscConfig().getSpeakerMaxDistance();

        if (main.distanceSquared(speaker.getLocation()) > (double) max * max) {
            return PairResult.TOO_FAR;
        }
        if (group.size() >= plugin.cdiscConfig().getSpeakerMaxPerGroup()) {
            return PairResult.GROUP_FULL;
        }
        return PairResult.OK;
    }

    public boolean addSpeaker(SpeakerGroup group, Block speaker) {
        if (canAdd(group, speaker) != PairResult.OK) return false;
        if (!group.addSpeaker(speaker.getLocation())) return false;
        byBlock.put(key(speaker.getLocation()), group);
        save();
        return true;
    }

    public boolean removeSpeaker(SpeakerGroup group, Block speaker) {
        if (!group.removeSpeaker(speaker.getLocation())) return false;
        byBlock.remove(key(speaker.getLocation()));
        save();
        return true;
    }

    public boolean promote(SpeakerGroup group, Block target) {
        if (!group.promote(target.getLocation())) return false;
        save();
        return true;
    }

    public void dissolve(SpeakerGroup group) {
        groups.remove(group.id());
        byBlock.remove(key(group.main()));
        for (Location speaker : group.speakers()) {
            byBlock.remove(key(speaker));
        }
        save();
    }

    public boolean onMemberRemoved(Block block) {
        SpeakerGroup group = groupAt(block);
        if (group == null) return false;

        if (group.isMain(block.getLocation())) {
            dissolve(group);
            return true;
        }
        removeSpeaker(group, block);
        return false;
    }

    private void load() {
        groups.clear();
        byBlock.clear();
        if (!file.exists()) return;

        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("groups");
        if (root == null) return;

        for (String rawId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) continue;

            try {
                Location main = parse(section.getString("main"));
                if (main == null) {

                    plugin.getLogger().warning("Speaker group '" + section.getString("name")
                            + "' refers to a missing world, dropping it");
                    continue;
                }

                String ownerRaw = section.getString("owner");
                SpeakerGroup group = new SpeakerGroup(
                        UUID.fromString(rawId),
                        section.getString("name", "?"),
                        ownerRaw == null ? null : UUID.fromString(ownerRaw),
                        main
                );

                for (String rawSpeaker : section.getStringList("speakers")) {
                    Location speaker = parse(rawSpeaker);
                    if (speaker != null) group.addSpeaker(speaker);
                }

                groups.put(group.id(), group);
                byBlock.put(key(group.main()), group);
                for (Location speaker : group.speakers()) {
                    byBlock.put(key(speaker), group);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping malformed speaker group '" + rawId + "': " + e);
            }
        }

        if (!groups.isEmpty()) {
            plugin.getLogger().info("Loaded " + groups.size() + " speaker group(s).");
        }
    }

    public void save() {
        FileConfiguration yaml = new YamlConfiguration();
        for (SpeakerGroup group : groups.values()) {
            String path = "groups." + group.id();
            yaml.set(path + ".name", group.name());
            yaml.set(path + ".owner", group.owner() == null ? null : group.owner().toString());
            yaml.set(path + ".main", serialize(group.main()));

            List<String> speakers = new ArrayList<>();
            for (Location speaker : group.speakers()) {
                speakers.add(serialize(speaker));
            }
            yaml.set(path + ".speakers", speakers);
        }

        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create the plugin folder, speaker groups not saved");
                return;
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save speaker groups: " + e);
        }
    }

    public void reload() {
        load();
    }

    static boolean sameBlock(Location a, Location b) {
        if (a == null || b == null) return false;
        if (a.getWorld() == null || b.getWorld() == null) return false;
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private static String key(Location location) {
        World world = location.getWorld();
        return (world == null ? "?" : world.getName())
                + ':' + location.getBlockX()
                + ':' + location.getBlockY()
                + ':' + location.getBlockZ();
    }

    private static String serialize(Location location) {
        return key(location);
    }

    private static Location parse(String raw) {
        if (raw == null) return null;
        int z = raw.lastIndexOf(':');
        if (z < 0) return null;
        int y = raw.lastIndexOf(':', z - 1);
        if (y < 0) return null;
        int x = raw.lastIndexOf(':', y - 1);
        if (x < 0) return null;

        World world = Bukkit.getWorld(raw.substring(0, x));
        if (world == null) return null;

        return new Location(world,
                Integer.parseInt(raw.substring(x + 1, y)),
                Integer.parseInt(raw.substring(y + 1, z)),
                Integer.parseInt(raw.substring(z + 1)));
    }
}
