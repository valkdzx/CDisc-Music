package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.api.event.DiscCreateEvent;
import dev.valkdz.cdisc.disc.ItemUtils;
import dev.valkdz.cdisc.feature.broadcast.BroadcastTrack;
import dev.valkdz.cdisc.feature.library.TrackLibrary;
import dev.valkdz.cdisc.feature.local.LocalMusicLibrary;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import dev.valkdz.cdisc.jukebox.queue.DiscQueue;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.permission.PermissionsConfig;
import dev.valkdz.cdisc.util.TimeUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LibraryGuiManager {

    public static final int SIZE = 54;
    public static final int PER_PAGE = 45;
    public static final int SLOT_EXIT = 45;
    public static final int SLOT_PREV = 46;
    public static final int SLOT_WRITE = 49;
    public static final int SLOT_NEXT = 52;
    public static final int SLOT_INFO = 53;

    private static final List<Material> DISCS = discs();

    private final Main plugin;
    private final Map<UUID, LibraryGuiHolder> open = new ConcurrentHashMap<>();

    public LibraryGuiManager(Main plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        LibraryGuiHolder holder = new LibraryGuiHolder(player.getUniqueId());
        TrackLibrary library = plugin.getTrackLibrary();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, message(player, "gui.library.title",
                String.valueOf(library.tracks(player.getUniqueId()).size()), String.valueOf(library.capacity())));
        holder.setInventory(inventory);
        render(player, holder);
        player.openInventory(inventory);
        open.put(player.getUniqueId(), holder);
    }

    void closed(Player player, LibraryGuiHolder holder) {
        open.remove(player.getUniqueId(), holder);
    }

    public void stop() {
        for (UUID id : List.copyOf(open.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) dev.valkdz.cdisc.util.Tasks.onEntity(plugin, player, player::closeInventory);
        }
        open.clear();
    }

    void render(Player player, LibraryGuiHolder holder) {
        Inventory inventory = holder.getInventory();
        List<TrackLibrary.Track> tracks = plugin.getTrackLibrary().tracks(holder.getOwner());
        int last = Math.max(0, (tracks.size() - 1) / PER_PAGE);
        holder.setPage(Math.max(0, Math.min(holder.getPage(), last)));
        if (holder.getSelected() != null && tracks.stream().noneMatch(t -> t.query().equals(holder.getSelected()))) {
            holder.setSelected(null);
        }
        int from = holder.getPage() * PER_PAGE;
        List<TrackLibrary.Track> page = tracks.subList(Math.min(from, tracks.size()), Math.min(from + PER_PAGE, tracks.size()));
        holder.show(page);

        ItemStack empty = pane(player, Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < PER_PAGE; slot++) {
            inventory.setItem(slot, slot < page.size()
                    ? trackItem(player, page.get(slot), page.get(slot).query().equals(holder.getSelected())) : empty);
        }
        ItemStack filler = pane(player, Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = PER_PAGE; slot < SIZE; slot++) inventory.setItem(slot, filler);

        inventory.setItem(SLOT_EXIT, named(Icons.head(player, GuiHeads.EXIT, GuiHeads.Legacy.EXIT),
                message(player, "gui.exit.name"), List.of()));
        if (holder.getPage() > 0) {
            inventory.setItem(SLOT_PREV, named(Icons.pick(player, GuiHeads.PAGE_PREV, Material.ARROW),
                    message(player, "gui.library.prev"), List.of()));
        }
        if (holder.getPage() < last) {
            inventory.setItem(SLOT_NEXT, named(Icons.pick(player, GuiHeads.PAGE_NEXT, Material.ARROW),
                    message(player, "gui.library.next"), List.of()));
        }
        inventory.setItem(SLOT_WRITE, writeItem(player, holder, tracks));
        inventory.setItem(SLOT_INFO, infoItem(player, holder, tracks.size(), last));
    }

    void turn(Player player, LibraryGuiHolder holder, int by) {
        holder.setPage(holder.getPage() + by);
        render(player, holder);
    }

    void select(Player player, LibraryGuiHolder holder, TrackLibrary.Track track) {
        holder.setSelected(track.query().equals(holder.getSelected()) ? null : track.query());
        render(player, holder);
    }

    void remove(Player player, LibraryGuiHolder holder, TrackLibrary.Track track) {
        if (plugin.getTrackLibrary().remove(holder.getOwner(), track.query())) {
            player.sendMessage("§a" + message(player, "library.removed", track.name()));
        }
        render(player, holder);
    }

    // Writes the chosen track onto disc in place; false when nothing was written.
    boolean writeSelected(Player player, LibraryGuiHolder holder, ItemStack disc) {
        TrackLibrary.Track chosen = plugin.getTrackLibrary().find(holder.getOwner(), holder.getSelected());
        if (chosen == null) {
            player.sendMessage("§e" + message(player, "library.pick_first"));
            return false;
        }
        return write(player, chosen, disc);
    }

    public boolean write(Player player, TrackLibrary.Track track, ItemStack disc) {
        PermissionsConfig perms = plugin.getPermissions();
        if (!perms.require(player, Action.DISC_CREATE)) return false;
        long wait = perms.cooldownRemaining(player);
        if (wait > 0) {
            player.sendMessage("§c" + message(player, "perms.cooldown", String.valueOf(wait)));
            return false;
        }
        if (!perms.isHostAllowed(player, track.query())) {
            String host = PermissionsConfig.hostOf(track.query());
            player.sendMessage("§c" + message(player, "perms.host_blocked", host == null ? "?" : host));
            return false;
        }
        boolean local = LocalMusicLibrary.isLocalQuery(track.query());
        if (local && !plugin.getLocalMusic().mayUse(player, LocalMusicLibrary.stripPrefix(track.query()))) {
            player.sendMessage("§c" + message(player, "local.no_permission"));
            return false;
        }
        String rejection = local ? null : perms.trackRejection(player, track.live(), track.lengthMs());
        if (rejection != null) {
            player.sendMessage("§c" + message(player, rejection, TimeUtils.format(perms.maxTrackSeconds() * 1000L)));
            return false;
        }

        String author = track.author() == null ? "" : track.author();
        DiscCreateEvent event = new DiscCreateEvent(player, disc,
                track.fallback() != null ? track.fallback() : track.query(), track.title(), author, track.lengthMs());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;

        ItemUtils.saveTrackToDisc(player, disc, track.query(), track.fallback(), track.title(), author,
                track.fetch(), track.hint());
        perms.markCreated(player);
        player.sendMessage("§a" + message(player, "library.written", track.name()));
        return true;
    }

    public void announce(Player player, TrackLibrary.Added added, TrackLibrary.Track track) {
        switch (added) {
            case ADDED -> player.sendMessage("§a" + message(player, "library.added", track.name()));
            case ALREADY -> player.sendMessage("§e" + message(player, "library.already", track.name()));
            case FULL -> player.sendMessage("§c" + message(player, "library.full",
                    String.valueOf(plugin.getTrackLibrary().capacity())));
        }
        refreshOpen(player.getUniqueId());
    }

    public void refreshOpen(UUID owner) {
        LibraryGuiHolder holder = open.get(owner);
        Player player = Bukkit.getPlayer(owner);
        if (holder != null && player != null) dev.valkdz.cdisc.util.Tasks.onEntity(plugin, player, () -> render(player, holder));
    }

    // The disc the jukebox is playing, as it would go into a library; null when it cannot.
    public TrackLibrary.Track playing(Block block) {
        PlaybackManager apm = plugin.getAudioPlayerManager();
        DiscQueue queue = apm.getQueue(block);
        ItemStack current = queue == null ? null : queue.getCurrent();
        ItemUtils.DiscData data = ItemUtils.readDiscData(current);
        if (data == null || ItemUtils.isLocked(current) || BroadcastTrack.isAddress(data.query())) return null;
        PlaybackManager.PlaybackInfo info = apm.getPlaybackInfo(block);
        boolean live = info != null ? info.live() : data.hint() != null && data.hint().live();
        long length = live ? 0 : info != null ? info.duration() : data.hint() != null ? data.hint().lengthMs() : 0;
        return new TrackLibrary.Track(data.query(), data.fallback(),
                data.title() == null ? "No name" : data.title(), data.author(), data.fetch(),
                Math.max(0, length), live, System.currentTimeMillis());
    }

    public ItemStack starItem(Player player, Block block) {
        TrackLibrary library = plugin.getTrackLibrary();
        TrackLibrary.Track track = library == null || !library.isEnabled() ? null : playing(block);
        if (track == null) return null;
        boolean liked = library.contains(player.getUniqueId(), track.query());
        ItemStack item = liked ? Icons.pick(player, GuiHeads.STAR_ON, Material.NETHER_STAR)
                : Icons.pick(player, GuiHeads.STAR_OFF, Material.FIREWORK_STAR);
        return named(item, message(player, liked ? "gui.star.liked" : "gui.star.unliked"),
                List.of(message(player, liked ? "gui.star.liked_lore" : "gui.star.unliked_lore")));
    }

    public void toggleStar(Player player, Block block) {
        TrackLibrary library = plugin.getTrackLibrary();
        TrackLibrary.Track track = library == null || !library.isEnabled() ? null : playing(block);
        if (track == null) return;
        if (library.remove(player.getUniqueId(), track.query())) {
            player.sendMessage("§e" + message(player, "library.removed", track.name()));
            refreshOpen(player.getUniqueId());
            return;
        }
        announce(player, library.add(player.getUniqueId(), track), track);
    }

    public static TrackLibrary.Track named(List<TrackLibrary.Track> tracks, String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        TrackLibrary.Track prefix = null;
        int prefixes = 0;
        for (TrackLibrary.Track track : tracks) {
            String own = track.name().toLowerCase(Locale.ROOT);
            if (own.equals(wanted)) return track;
            if (own.startsWith(wanted)) {
                prefix = track;
                prefixes++;
            }
        }
        return prefixes == 1 ? prefix : null;
    }

    private ItemStack trackItem(Player player, TrackLibrary.Track track, boolean selected) {
        ItemStack item = new ItemStack(DISCS.get(Math.floorMod(track.query().hashCode(), DISCS.size())));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName((selected ? "§a▶ " : "§f") + trim(Normalizer.normalize(track.title(), Normalizer.Form.NFC), 40));
        List<String> lore = new ArrayList<>();
        if (track.author() != null && !track.author().isBlank()) {
            lore.add("§7" + trim(Normalizer.normalize(track.author(), Normalizer.Form.NFC), 40));
        }
        if (track.live()) {
            lore.add("§8" + message(player, "command.player.info_live"));
        } else if (track.lengthMs() > 0) {
            lore.add("§8" + TimeUtils.format(track.lengthMs()));
        }
        lore.add("");
        lore.add(message(player, selected ? "gui.library.selected" : "gui.library.select"));
        lore.add(message(player, "gui.library.remove"));
        meta.setLore(lore);
        if (selected) glint(meta);
        ItemUtils.hideSongLine(meta, item);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack writeItem(Player player, LibraryGuiHolder holder, List<TrackLibrary.Track> tracks) {
        TrackLibrary.Track chosen = tracks.stream()
                .filter(t -> Objects.equals(t.query(), holder.getSelected())).findFirst().orElse(null);
        List<String> lore = new ArrayList<>();
        lore.add(chosen == null ? message(player, "gui.library.write_none")
                : message(player, "gui.library.write_chosen", trim(chosen.name(), 40)));
        lore.add(message(player, "gui.library.write_hint"));
        return named(Icons.pick(player, GuiHeads.LIBRARY_WRITE, Material.JUKEBOX),
                message(player, "gui.library.write_name"), lore);
    }

    private ItemStack infoItem(Player player, LibraryGuiHolder holder, int count, int lastPage) {
        List<String> lore = new ArrayList<>();
        lore.add(message(player, "gui.library.count", String.valueOf(count),
                String.valueOf(plugin.getTrackLibrary().capacity())));
        if (lastPage > 0) {
            lore.add(message(player, "gui.library.page", String.valueOf(holder.getPage() + 1), String.valueOf(lastPage + 1)));
        }
        lore.add(message(player, count == 0 ? "gui.library.empty" : "gui.library.hint"));
        return named(Icons.head(player, GuiHeads.INFO, GuiHeads.Legacy.INFO), message(player, "gui.library.name"), lore);
    }

    private static ItemStack named(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack pane(Player player, Material material) {
        return named(new ItemStack(Icons.pane(player, material)), " ", List.of());
    }

    private static void glint(ItemMeta meta) {
        Enchantment shine = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        if (shine == null) return;
        meta.addEnchant(shine, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }

    private static List<Material> discs() {
        List<Material> discs = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.name().startsWith("MUSIC_DISC_") && !material.name().startsWith("LEGACY_")) discs.add(material);
        }
        return discs.isEmpty() ? List.of(Material.JUKEBOX) : List.copyOf(discs);
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private String message(Player player, String key, Object... args) {
        return plugin.getMessageManager().get(player, key, args);
    }
}
