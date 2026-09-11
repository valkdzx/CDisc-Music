package dev.valkdz.cdisc.gui;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Chat;
import dev.valkdz.cdisc.util.ItemUtils;
import dev.valkdz.cdisc.util.TimeUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlaylistGuiManager {

    public static final int SIZE = 54;

    public static final int SLOTS_PER_PAGE = 45;

    public static final int SLOT_EXIT = 45;
    public static final int SLOT_PREV = 48;
    public static final int SLOT_INFO = 49;
    public static final int SLOT_NEXT = 50;

    private final Main plugin;

    private final Map<UUID, PlaylistGuiHolder> openScreens = new ConcurrentHashMap<>();

    public PlaylistGuiManager(Main plugin) {
        this.plugin = plugin;
    }

    public void stop() {
        for (UUID id : List.copyOf(openScreens.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) player.closeInventory();
        }
        openScreens.clear();
    }

    void forget(Player player) {
        openScreens.remove(player.getUniqueId());
    }

    public void openFor(Player player, String query) {
        String resolved = plugin.getAudioPlayerManager().getTrackLoader().resolveQuery(query);
        if (resolved == null) {
            player.sendMessage("§c" + plugin.getMessageManager().get(player, "lavaplayer.track.invalid_query"));
            return;
        }

        player.sendMessage("§e" + plugin.getMessageManager().get(player, "playlist.loading"));

        plugin.getAudioPlayerManager().getTrackLoader().loadItem(resolved, new AudioLoadResultHandler() {
            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrack> tracks = new ArrayList<>(playlist.getTracks());
                if (tracks.isEmpty()) {
                    reply(player, "§c", "playlist.empty");
                    return;
                }

                int found = tracks.size();
                String refusal = null;
                for (Iterator<AudioTrack> it = tracks.iterator(); it.hasNext(); ) {
                    AudioTrack track = it.next();
                    String why = plugin.getPermissions().trackRejection(
                            player, track.getInfo().isStream, track.getInfo().length);
                    if (why == null) continue;
                    if (refusal == null) refusal = why;
                    it.remove();
                }
                if (tracks.isEmpty()) {

                    reply(player, "§c",
                            "perms.track_too_long".equals(refusal)
                                    ? "playlist.all_too_long" : refusal,
                            TimeUtils.format(plugin.getPermissions().maxTrackSeconds() * 1000L));
                    return;
                }
                int omitted = found - tracks.size();
                if (omitted > 0) {
                    reply(player, "§e", "playlist.some_too_long", String.valueOf(omitted));
                }
                String name = playlist.getName() == null ? "?" : playlist.getName();
                Bukkit.getScheduler().runTask(plugin, () -> open(player, query, name, tracks));
            }

            @Override
            public void trackLoaded(AudioTrack track) {
                reply(player, "§c", "playlist.not_a_playlist");
            }

            @Override
            public void noMatches() {
                reply(player, "§c", "lavaplayer.track.notfound");
            }

            @Override
            public void loadFailed(FriendlyException e) {
                reply(player, "§c", "playlist.load_failed");
            }
        });
    }

    private void reply(Player player, String colour, String key, String... args) {
        Bukkit.getScheduler().runTask(plugin, () ->
                player.sendMessage(colour + plugin.getMessageManager().get(player, key, args)));
    }

    private void open(Player player, String query, String name, List<AudioTrack> tracks) {
        PlaylistGuiHolder holder = new PlaylistGuiHolder(query, name, tracks);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.getMessageManager().get(player, "playlist.title", trim(name)));
        holder.setInventory(inventory);
        render(player, holder, inventory);
        player.openInventory(inventory);
        openScreens.put(player.getUniqueId(), holder);
    }

    public void render(Player player, PlaylistGuiHolder holder, Inventory inventory) {
        ItemStack blocked = placeholder(Material.GRAY_STAINED_GLASS_PANE, " ");
        int slots = holder.slotsOnPage();

        for (int slot = 0; slot < SLOTS_PER_PAGE; slot++) {
            if (slot >= slots) {

                inventory.setItem(slot, blocked);
                continue;
            }
            inventory.setItem(slot, holder.placedAt(holder.trackIndexAt(slot)));
        }

        ItemStack filler = placeholder(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = SLOTS_PER_PAGE; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(SLOT_EXIT, placeholder(Material.BARRIER,
                plugin.getMessageManager().get(player, "playlist.exit")));
        inventory.setItem(SLOT_INFO, infoItem(player, holder));

        if (holder.hasPages()) {
            inventory.setItem(SLOT_PREV, pageItem(player, "playlist.prev", holder.getPage() > 0));
            inventory.setItem(SLOT_NEXT, pageItem(player, "playlist.next",
                    holder.getPage() < holder.lastPage()));
        }
    }

    public void harvest(PlaylistGuiHolder holder, Inventory inventory) {
        int slots = holder.slotsOnPage();
        for (int slot = 0; slot < slots; slot++) {
            holder.place(holder.trackIndexAt(slot), inventory.getItem(slot));
        }
    }

    public void writeAndReturn(Player player, PlaylistGuiHolder holder) {
        forget(player);

        Map<Integer, ItemStack> placed = holder.allPlaced();
        if (placed.isEmpty()) return;

        List<AudioTrack> tracks = holder.getTracks();
        List<ItemStack> returning = new ArrayList<>();
        int written = 0;
        int total = placed.size();

        for (Map.Entry<Integer, ItemStack> entry : placed.entrySet()) {
            int index = entry.getKey();
            ItemStack disc = entry.getValue();
            if (disc == null || index < 0 || index >= tracks.size()) continue;

            AudioTrack track = tracks.get(index);
            String author = track.getInfo().author == null ? "Unknown" : track.getInfo().author;
            String title = track.getInfo().title == null ? "No name" : track.getInfo().title;

            String uri = track.getInfo().uri == null ? holder.getQuery() : track.getInfo().uri;

            ItemUtils.saveTrackToDisc(disc, uri, null,
                    Normalizer.normalize(title, Normalizer.Form.NFC),
                    Normalizer.normalize(author, Normalizer.Form.NFC),
                    null);

            written++;
            Chat.actionBar(player, plugin.getMessageManager()
                    .get(player, "playlist.progress", String.valueOf(written), String.valueOf(total)),
                    255, 200, 60);

            returning.add(disc);
        }

        placed.clear();
        giveBack(player, returning);

        int missing = tracks.size() - written;
        player.sendMessage("§a" + plugin.getMessageManager().get(player, "playlist.done",
                String.valueOf(written), String.valueOf(tracks.size())));
        if (missing > 0) {
            player.sendMessage("§e" + plugin.getMessageManager().get(player, "playlist.missing",
                    String.valueOf(missing)));
        }
    }

    private void giveBack(Player player, List<ItemStack> discs) {
        if (discs.isEmpty()) return;

        if (!plugin.isEnabled()) {
            handOver(player, discs);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> handOver(player, discs));
    }

    private void handOver(Player player, List<ItemStack> discs) {
        int dropped = 0;
        for (ItemStack disc : discs) {
            for (ItemStack leftover : player.getInventory().addItem(disc).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                dropped++;
            }
        }
        if (dropped > 0) {
            player.sendMessage("§e" + plugin.getMessageManager().get(player, "playlist.dropped",
                    String.valueOf(dropped)));
        }
    }

    private ItemStack infoItem(Player player, PlaylistGuiHolder holder) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName("§6" + trim(holder.getPlaylistName()));
        List<String> lore = new ArrayList<>();
        lore.add(plugin.getMessageManager().get(player, "playlist.info.tracks",
                String.valueOf(holder.getTracks().size())));
        if (holder.hasPages()) {
            lore.add(plugin.getMessageManager().get(player, "playlist.info.page",
                    String.valueOf(holder.getPage() + 1), String.valueOf(holder.lastPage() + 1)));
        }
        lore.add(plugin.getMessageManager().get(player, "playlist.info.hint"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageItem(Player player, String key, boolean enabled) {
        ItemStack item = new ItemStack(enabled ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName((enabled ? "§f" : "§8") + plugin.getMessageManager().get(player, key));
        item.setItemMeta(meta);
        return item;
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

    private static String trim(String name) {
        return name.length() <= 24 ? name : name.substring(0, 21) + "...";
    }

    public static boolean isBlankDisc(ItemStack item) {
        return ItemUtils.isDisc(item) && !ItemUtils.isCdiscDisc(item);
    }
}
