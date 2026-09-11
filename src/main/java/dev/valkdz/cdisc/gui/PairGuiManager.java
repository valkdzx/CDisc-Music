package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.speaker.SpeakerGroup;
import dev.valkdz.cdisc.speaker.SpeakerGroupManager;
import dev.valkdz.cdisc.speaker.SpeakerSettings;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PairGuiManager {

    public static final int SIZE = 27;

    public static final int SLOT_MANAGE_BACK = 0;
    public static final int SLOT_INFO = 2;
    public static final int SLOT_ADD = 4;
    public static final int SLOT_DISSOLVE = 6;

    public static final int SLOT_BACK = 0;

    public static final int SETTINGS_SIZE = 9;
    public static final int SLOT_SETTINGS_BACK = 0;
    public static final int SLOT_NAME = 1;
    public static final int SLOT_CHANNEL = 2;
    public static final int SLOT_VOLUME = 3;
    public static final int SLOT_PARTICLES = 4;
    public static final int SLOT_UNLINK = 5;
    public static final int SLOT_PROMOTE = 6;

    public static final int ROW_START = 9;
    public static final int ROW_CAPACITY = SIZE - ROW_START;

    private final Map<UUID, Block> awaitingName = new ConcurrentHashMap<>();

    private final Map<UUID, Block> awaitingTag = new ConcurrentHashMap<>();

    private final Main plugin;

    public PairGuiManager(Main plugin) {
        this.plugin = plugin;
    }

    public void promptForName(Player player, Block block) {
        if (!plugin.getPermissions().allows(player, Action.PAIR_CREATE)) return;

        awaitingName.put(player.getUniqueId(), block);
        player.closeInventory();
        player.sendMessage(plugin.getMessageManager().get(player, "gui.pair.name_prompt"));
    }

    public Block getAwaitingName(Player player) {
        return awaitingName.get(player.getUniqueId());
    }

    public void cancelNaming(Player player) {
        awaitingName.remove(player.getUniqueId());
        awaitingTag.remove(player.getUniqueId());
    }

    public void promptForTag(Player player, Block block) {
        awaitingTag.put(player.getUniqueId(), block);
        player.closeInventory();
        player.sendMessage(plugin.getMessageManager().get(player, "gui.speaker.tag_prompt"));
    }

    public Block getAwaitingTag(Player player) {
        return awaitingTag.get(player.getUniqueId());
    }

    public void openManage(Player player, Block block) {
        if (!plugin.getPermissions().allows(player, Action.PAIR_MANAGE)) return;

        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(block);
        if (group == null) return;

        Block main = group.main().getBlock();
        PairGuiHolder holder = new PairGuiHolder(PairGuiHolder.Mode.MANAGE, main);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.getMessageManager().get(player, "gui.pair.manage_title", group.name()));
        holder.setInventory(inventory);

        ItemStack filler = filler();
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, filler);

        inventory.setItem(SLOT_MANAGE_BACK, backItem(player));
        inventory.setItem(SLOT_INFO, infoItem(player, group));
        inventory.setItem(SLOT_ADD, addItem(player, group));
        inventory.setItem(SLOT_DISSOLVE, dissolveItem(player));

        List<Block> members = new ArrayList<>();
        members.add(main);
        for (Location speaker : group.speakers()) {
            members.add(speaker.getBlock());
        }

        List<Block> shown = new ArrayList<>();
        for (int i = 0; i < members.size() && i < ROW_CAPACITY; i++) {
            Block member = members.get(i);
            shown.add(member);
            inventory.setItem(ROW_START + i, memberItem(player, group, member, i == 0));
        }
        holder.setTargets(shown);

        player.openInventory(inventory);
    }

    public void openPicker(Player player, Block main) {
        if (!plugin.getPermissions().allows(player, Action.PAIR_MANAGE)) return;

        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(main);
        if (group == null) return;

        PairGuiHolder holder = new PairGuiHolder(PairGuiHolder.Mode.PICKER, main);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.getMessageManager().get(player, "gui.pair.picker_title"));
        holder.setInventory(inventory);

        ItemStack filler = filler();
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, filler);
        inventory.setItem(SLOT_BACK, backItem(player));

        List<Block> candidates = findCandidates(group);
        for (int i = 0; i < candidates.size(); i++) {
            inventory.setItem(ROW_START + i, candidateItem(player, group, candidates.get(i)));
        }
        holder.setTargets(candidates);

        if (candidates.isEmpty()) {
            inventory.setItem(ROW_START + 4, placeholder(Material.BARRIER,
                    plugin.getMessageManager().get(player, "gui.pair.picker_empty")));
        }

        player.openInventory(inventory);
    }

    public void openSettings(Player player, Block main, Block subject) {
        if (!plugin.getPermissions().allows(player, Action.PAIR_SETTINGS)) return;

        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(main);
        boolean standalone = group == null;

        if (standalone && !main.equals(subject)) return;

        SpeakerSettings settings = SpeakerSettings.of(subject);
        boolean isMain = standalone || group.isMain(subject.getLocation());
        String roleKey = standalone ? "gui.speaker.standalone"
                : (isMain ? "gui.pair.member.main" : "gui.pair.member.speaker");

        PairGuiHolder holder = new PairGuiHolder(PairGuiHolder.Mode.SETTINGS, main, subject);
        Inventory inventory = Bukkit.createInventory(holder, SETTINGS_SIZE,
                plugin.getMessageManager().get(player, "gui.speaker.title",
                        displayName(player, settings, roleKey)));
        holder.setInventory(inventory);

        ItemStack filler = filler();
        for (int i = 0; i < SETTINGS_SIZE; i++) inventory.setItem(i, filler);

        inventory.setItem(SLOT_SETTINGS_BACK, backItem(player));
        inventory.setItem(SLOT_NAME, nameItem(player, subject, settings, roleKey));
        inventory.setItem(SLOT_CHANNEL, channelItem(player, settings));
        inventory.setItem(SLOT_VOLUME, volumeItem(player, settings));
        inventory.setItem(SLOT_PARTICLES, particlesItem(player, settings));

        if (!isMain) {
            inventory.setItem(SLOT_UNLINK, unlinkItem(player));
            inventory.setItem(SLOT_PROMOTE, promoteItem(player));
        }

        player.openInventory(inventory);
    }

    private ItemStack nameItem(Player player, Block block, SpeakerSettings settings, String roleKey) {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(displayName(player, settings, roleKey));
        meta.setLore(List.of(
                plugin.getMessageManager().get(player, "gui.pair.member.at", describe(block.getLocation())),
                plugin.getMessageManager().get(player, "gui.speaker.tag_hint")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack channelItem(Player player, SpeakerSettings settings) {

        ItemStack item = new ItemStack(Material.BREWING_STAND);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.speaker.channel"));
        List<String> lore = new ArrayList<>();
        for (SpeakerSettings.Channel option : SpeakerSettings.Channel.values()) {
            String label = plugin.getMessageManager().get(player, "gui.channels.mode." + option.key());
            lore.add((option == settings.channel() ? "§a▶ §f" : "§8• §7") + label);
        }
        lore.add(plugin.getMessageManager().get(player, "gui.speaker.channel_hint"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack volumeItem(Player player, SpeakerSettings settings) {
        boolean muted = settings.isMuted();

        ItemStack item = new ItemStack(
                muted ? Material.BARRIER : Material.NOTE_BLOCK,
                muted ? 1 : Math.max(1, settings.volume() / SpeakerSettings.VOLUME_STEP));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(muted
                ? plugin.getMessageManager().get(player, "gui.speaker.muted")
                : plugin.getMessageManager().get(player, "gui.speaker.volume",
                        String.valueOf(settings.volume())));
        meta.setLore(List.of(plugin.getMessageManager().get(player, "gui.speaker.volume_hint")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack particlesItem(Player player, SpeakerSettings settings) {
        ItemStack item = new ItemStack(settings.particles() ? Material.LIME_WOOL : Material.GRAY_WOOL);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.speaker.particles"));
        meta.setLore(List.of(plugin.getMessageManager().get(player,
                settings.particles() ? "gui.speaker.particles_on" : "gui.speaker.particles_off")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack promoteItem(Player player) {
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.speaker.promote"));
        meta.setLore(List.of(plugin.getMessageManager().get(player, "gui.speaker.promote_hint")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack unlinkItem(Player player) {
        ItemStack item = new ItemStack(Material.RED_WOOL);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.speaker.unlink"));
        meta.setLore(List.of(plugin.getMessageManager().get(player, "gui.speaker.unlink_hint")));
        item.setItemMeta(meta);
        return item;
    }

    private List<Block> findCandidates(SpeakerGroup group) {
        List<Block> found = new ArrayList<>();

        Location main = group.main();
        World world = main.getWorld();
        if (world == null) return found;

        int range = plugin.cdiscConfig().getSpeakerMaxDistance();
        double rangeSq = (double) range * range;

        int chunkRadius = (range >> 4) + 1;
        int centreX = main.getBlockX() >> 4;
        int centreZ = main.getBlockZ() >> 4;

        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();

        for (int cx = centreX - chunkRadius; cx <= centreX + chunkRadius; cx++) {
            for (int cz = centreZ - chunkRadius; cz <= centreZ + chunkRadius; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                Chunk chunk = world.getChunkAt(cx, cz);

                for (BlockState state : chunk.getTileEntities()) {
                    if (!(state instanceof Jukebox)) continue;

                    Block block = state.getBlock();
                    if (block.getLocation().distanceSquared(main) > rangeSq) continue;
                    if (groups.groupAt(block) != null) continue;

                    found.add(block);
                    if (found.size() >= ROW_CAPACITY) return found;
                }
            }
        }
        return found;
    }

    private ItemStack infoItem(Player player, SpeakerGroup group) {
        ItemStack item = new ItemStack(Material.NOTE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.pair.info.name", group.name()));
        List<String> lore = new ArrayList<>();
        lore.add(plugin.getMessageManager().get(player, "gui.pair.info.members",
                String.valueOf(group.size()), String.valueOf(plugin.cdiscConfig().getSpeakerMaxPerGroup())));
        lore.add(plugin.getMessageManager().get(player, "gui.pair.info.main", describe(group.main())));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack addItem(Player player, SpeakerGroup group) {
        boolean full = group.size() >= plugin.cdiscConfig().getSpeakerMaxPerGroup();
        ItemStack item = new ItemStack(full ? Material.BARRIER : Material.LIME_WOOL);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player,
                full ? "gui.pair.add.full" : "gui.pair.add.name"));
        meta.setLore(List.of(plugin.getMessageManager().get(player, "gui.pair.add.hint",
                String.valueOf(plugin.cdiscConfig().getSpeakerMaxDistance()))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack dissolveItem(Player player) {
        ItemStack item = new ItemStack(Material.RED_WOOL);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.pair.dissolve.name"));
        meta.setLore(List.of(plugin.getMessageManager().get(player, "gui.pair.dissolve.hint")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack memberItem(Player player, SpeakerGroup group, Block block, boolean isMain) {
        SpeakerSettings settings = SpeakerSettings.of(block);

        ItemStack item = new ItemStack(isMain ? Material.JUKEBOX : Material.NOTE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(displayName(player, settings,
                isMain ? "gui.pair.member.main" : "gui.pair.member.speaker"));

        List<String> lore = new ArrayList<>();
        if (settings.tag() != null && !settings.tag().isEmpty()) {
            lore.add(plugin.getMessageManager().get(player,
                    isMain ? "gui.pair.member.role_main" : "gui.pair.member.role_speaker"));
        }
        lore.add(plugin.getMessageManager().get(player, "gui.pair.member.at", describe(block.getLocation())));
        lore.add(plugin.getMessageManager().get(player, "gui.pair.member.channel",
                plugin.getMessageManager().get(player, "gui.channels.mode." + settings.channel().key())));
        lore.add(settings.isMuted()
                ? plugin.getMessageManager().get(player, "gui.speaker.muted")
                : plugin.getMessageManager().get(player, "gui.pair.member.volume",
                        String.valueOf(settings.volume())));
        lore.add(plugin.getMessageManager().get(player, "gui.pair.member.settings_hint"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack candidateItem(Player player, SpeakerGroup group, Block block) {
        SpeakerSettings settings = SpeakerSettings.of(block);

        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(displayName(player, settings, "gui.pair.candidate.name"));
        List<String> lore = new ArrayList<>();
        lore.add(plugin.getMessageManager().get(player, "gui.pair.member.at", describe(block.getLocation())));
        lore.add(plugin.getMessageManager().get(player, "gui.pair.candidate.distance",
                String.valueOf((int) block.getLocation().distance(group.main()))));
        lore.add(plugin.getMessageManager().get(player, "gui.pair.candidate.hint"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String displayName(Player player, SpeakerSettings settings, String fallbackKey) {
        String tag = settings.tag();
        if (tag != null && !tag.isEmpty()) return "§f" + tag;
        return plugin.getMessageManager().get(player, fallbackKey);
    }

    private ItemStack backItem(Player player) {
        return placeholder(Material.ARROW, plugin.getMessageManager().get(player, "gui.pair.back"));
    }

    private ItemStack filler() {
        return placeholder(Material.BLACK_STAINED_GLASS_PANE, " ");
    }

    private ItemStack placeholder(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String describe(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }
}
