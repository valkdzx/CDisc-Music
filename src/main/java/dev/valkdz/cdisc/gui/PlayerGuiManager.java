package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.config.Config;
import dev.valkdz.cdisc.feature.lyrics.LyricsMode;
import dev.valkdz.cdisc.feature.lyrics.LyricsPrefs;
import dev.valkdz.cdisc.feature.lyrics.LyricsRenderer;
import dev.valkdz.cdisc.feature.lyrics.LyricsService;
import dev.valkdz.cdisc.feature.lyrics.LyricsStyle;
import dev.valkdz.cdisc.feature.lyrics.chat.ChatFeed;
import dev.valkdz.cdisc.jukebox.BeaconUtils;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import dev.valkdz.cdisc.jukebox.queue.DiscQueue;
import dev.valkdz.cdisc.jukebox.queue.RepeatMode;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.util.HeadUtils;
import dev.valkdz.cdisc.util.Tasks;
import dev.valkdz.cdisc.util.TimeUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.ItemMeta;

import dev.valkdz.cdisc.config.PlayerPrefs;
import dev.valkdz.cdisc.feature.speaker.SpeakerGroup;
import dev.valkdz.cdisc.feature.speaker.SpeakerSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerGuiManager {

    public static final int GUI_SIZE = 18;

    public static final int SLOT_INFO = 0;
    public static final int SLOT_QUEUE = 1;
    public static final int SLOT_SEEK_BACK = 2;
    public static final int SLOT_PREV = 3;
    public static final int SLOT_PAUSE = 4;
    public static final int SLOT_NEXT = 5;
    public static final int SLOT_SEEK_FORWARD = 6;
    public static final int SLOT_REPEAT = 7;
    public static final int SLOT_SHUFFLE = 8;
    public static final int SLOT_ADVANCED = 9;
    public static final int SLOT_BROADCAST = 10;
    public static final int SLOT_LYRICS = 13;
    public static final int SLOT_EXIT = 17;

    public static final int ADV_PAIR = 0;
    public static final int ADV_CHANNELS = 1;
    public static final int ADV_BEACON = 2;
    public static final int ADV_VOLUME = 3;
    public static final int ADV_LOCAL_VOLUME = 4;
    public static final int ADV_CROSSFADE = 5;
    public static final int ADV_PORTABLE = 6;
    public static final int ADV_TRACK_MESSAGES = 7;
    public static final int ADV_VIEW = 8;
    public static final int ADV_BACK = 9;

    public static final int LOCAL_TRACK_MESSAGES = 11;
    public static final int LOCAL_VOLUME = 15;
    private static final long SEEK_STEP_MS = 5000L;
    private final Map<Block, Set<Player>> openViewers = new ConcurrentHashMap<>();
    private final Map<UUID, Block> awaitingSeekChat = new ConcurrentHashMap<>();

    private final Map<UUID, String> lastLyrics = new ConcurrentHashMap<>();
    private final Main plugin;
    private Tasks.Handle titleUpdateTask;

    public PlayerGuiManager(Main plugin) {
        this.plugin = plugin;
    }

    public void start() {

        titleUpdateTask = Tasks.globalTimer(plugin, this::refreshOpenScreens, 1L, 10L);
    }

    public void stop() {
        if (titleUpdateTask != null) {
            titleUpdateTask.cancel();
            titleUpdateTask = null;
        }
    }

    private void refreshOpenScreens() {
        if (openViewers.isEmpty()) return;

        for (Map.Entry<Block, Set<Player>> entry : openViewers.entrySet()) {
            Block block = entry.getKey();
            PlaybackManager.PlaybackInfo info = plugin.getAudioPlayerManager().getPlaybackInfo(block);
            if (info == null) continue;

            String progress_position = TimeUtils.formatCompact(info.position());
            String progress_duration = TimeUtils.formatCompact(info.duration());
            for (Player player : entry.getValue()) {
                if (!player.isOnline()) continue;

                Tasks.onEntity(plugin, player, () -> {
                    InventoryView view = player.getOpenInventory();
                    if (!(view.getTopInventory().getHolder() instanceof PlayerGuiHolder holder)
                            || !holder.getBlock().equals(block) || holder.isAdvanced()) {
                        return;
                    }

                    String title = info.live()
                            ? plugin.getMessageManager().get(player, "gui.title_live")
                            : plugin.getMessageManager().get(player, "gui.title_progress",
                                    progress_position, progress_duration);
                    view.setTitle(title);

                    refreshLyricsItem(player, view.getTopInventory(), block, info);
                });
            }
        }
    }

    private void refreshLyricsItem(Player player, Inventory inventory, Block block,
                                   PlaybackManager.PlaybackInfo info) {
        if (!plugin.cdiscConfig().isLyricsEnabled()) return;

        ItemStack item = buildLyricsItem(player, block, info);
        if (item == null) return;

        ItemMeta meta = item.getItemMeta();
        String rendered = meta == null || meta.getLore() == null ? "" : String.join("\n", meta.getLore());
        if (rendered.equals(lastLyrics.get(player.getUniqueId()))) return;

        lastLyrics.put(player.getUniqueId(), rendered);
        inventory.setItem(SLOT_LYRICS, item);
    }

    public void open(Player player, Block block) {

        if (!plugin.getPermissions().allows(player, Action.PLAYER_GUI)) return;
        if (!plugin.getRegionGuard().require(player, block)) return;

        PlaybackManager apm = plugin.getAudioPlayerManager();
        if (!canOpen(apm, block)) {

            player.sendMessage("§c" + plugin.getMessageManager()
                    .get(player, "command.player.no_jukebox"));
            return;
        }

        boolean local = plugin.getJukeboxViewers().heldByOther(block, player);
        if (local && !hasLocalControls(player, block)) {
            player.sendMessage("§c" + plugin.getMessageManager().get(player, "gui.queue.busy"));
            return;
        }

        if (!local) plugin.getScreenPreferences().setLast(player.getUniqueId(), ScreenPreferences.Screen.PLAYER);

        if (!local && dev.valkdz.cdisc.gui.dialog.Dialogs.playerScreenWanted(plugin, player)
                && dev.valkdz.cdisc.gui.dialog.Dialogs.openPlayer(plugin, player, block)) {
            return;
        }

        show(player, block, new PlayerGuiHolder(block, apm.getGeneration(block), local, false));
    }

    public void openAdvanced(Player player, Block block) {
        if (!plugin.getPermissions().allows(player, Action.PLAYER_GUI)) return;
        if (!plugin.getRegionGuard().require(player, block)) return;

        PlaybackManager apm = plugin.getAudioPlayerManager();
        if (!canOpen(apm, block) || plugin.getJukeboxViewers().heldByOther(block, player)) {
            open(player, block);
            return;
        }

        plugin.getScreenPreferences().setLast(player.getUniqueId(), ScreenPreferences.Screen.ADVANCED);
        show(player, block, new PlayerGuiHolder(block, apm.getGeneration(block), false, true));
    }

    public void openRemembered(Player player, Block block) {
        ScreenPreferences.Screen last = plugin.getScreenPreferences().last(player.getUniqueId());
        if (last == null) {
            last = plugin.getAudioPlayerManager().queueSize(block) > 1
                    ? ScreenPreferences.Screen.QUEUE : ScreenPreferences.Screen.PLAYER;
        }

        switch (last) {
            case QUEUE -> {
                if (may(player, Action.QUEUE_OPEN)) {
                    plugin.getQueueGuiManager().open(player, block);
                } else {
                    open(player, block);
                }
            }
            case ADVANCED -> openAdvanced(player, block);
            default -> open(player, block);
        }
    }

    private void show(Player player, Block block, PlayerGuiHolder holder) {
        boolean local = holder.isLocal();
        String title = plugin.getMessageManager().get(player,
                holder.isAdvanced() ? "gui.advanced.title" : "gui.title");
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, title);
        holder.setInventory(inventory);

        refresh(player, inventory, block);
        player.openInventory(inventory);
        if (local) {
            player.sendMessage("§7" + plugin.getMessageManager().get(player, "gui.local.notice"));
        } else {
            plugin.getJukeboxViewers().claim(block, player);
        }

        openViewers.computeIfAbsent(block, b -> ConcurrentHashMap.newKeySet()).add(player);
    }

    public boolean canOpen(PlaybackManager apm, Block block) {
        if (apm.hasActiveSession(block)) return true;
        if (apm.getQueue(block) != null) return true;

        if (plugin.getPortableJukeboxManager() != null
                && plugin.getPortableJukeboxManager().carryOfBlock(block) != null) {
            return true;
        }

        return block.getState() instanceof org.bukkit.block.Jukebox jukebox
                && jukebox.hasRecord();
    }

    public void onClosed(Player player, Block block) {
        plugin.getJukeboxViewers().release(block, player);
        lastLyrics.remove(player.getUniqueId());
        Set<Player> viewers = openViewers.get(block);
        if (viewers == null) return;
        viewers.remove(player);
        if (viewers.isEmpty()) openViewers.remove(block);
    }

    public void forceCloseFor(Block block) {
        plugin.getJukeboxViewers().clear(block);
        Set<Player> viewers = openViewers.remove(block);
        if (viewers == null) return;
        for (Player p : viewers) {
            if (p.isOnline()) Tasks.onEntity(plugin, p, p::closeInventory);
        }
    }

    public void refresh(Player player, Block block) {
        InventoryView view = player.getOpenInventory();
        Inventory top = view.getTopInventory();
        if (!(top.getHolder() instanceof PlayerGuiHolder holder) || !holder.getBlock().equals(block)) return;

        refresh(player, top, block);
    }

    private boolean hasLocalControls(Player player, Block block) {
        return (plugin.cdiscConfig().isLyricsEnabled()
                && (may(player, Action.LYRICS_TOGGLE) || may(player, Action.LYRICS_PRESET)))
                || may(player, Action.PLAYER_MESSAGES)
                || (may(player, Action.PLAYER_LOCAL_VOLUME) && carries(player, block));
    }

    private void refreshLocal(Player player, Inventory inventory, Block block,
                              PlaybackManager.PlaybackInfo info) {
        ItemStack filler = fillerPane();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(SLOT_INFO, buildLocalNoticeItem(player));
        inventory.setItem(SLOT_EXIT, buildExitItem(player));

        ItemStack lyrics = may(player, Action.LYRICS_TOGGLE) || may(player, Action.LYRICS_PRESET)
                ? buildLyricsItem(player, block, info) : null;
        inventory.setItem(SLOT_LYRICS, lyrics != null ? lyrics : filler);
        rememberLyrics(player, lyrics);

        inventory.setItem(LOCAL_TRACK_MESSAGES, may(player, Action.PLAYER_MESSAGES)
                ? buildTrackMessagesItem(player) : filler);

        ItemStack local = may(player, Action.PLAYER_LOCAL_VOLUME)
                ? buildLocalVolumeItem(player, block) : null;
        inventory.setItem(LOCAL_VOLUME, local != null ? local : filler);
    }

    private ItemStack buildLocalNoticeItem(Player player) {
        ItemStack item = new ItemStack(Material.STRUCTURE_VOID);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§e" + plugin.getMessageManager().get(player, "gui.local.name"));
            meta.setLore(List.of("§7" + plugin.getMessageManager().get(player, "gui.local.lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    boolean carries(Player player, Block block) {
        if (plugin.getPortableJukeboxManager() == null) return false;

        var carry = plugin.getPortableJukeboxManager().carryOfBlock(block);
        return carry != null && carry.carrier().equals(player.getUniqueId());
    }

    private void refresh(Player player, Inventory inventory, Block block) {
        PlaybackManager apm = plugin.getAudioPlayerManager();
        PlaybackManager.PlaybackInfo info = apm.getPlaybackInfo(block);

        if (inventory.getHolder() instanceof PlayerGuiHolder holder) {
            if (holder.isLocal()) {
                refreshLocal(player, inventory, block, info);
                return;
            }
            if (holder.isAdvanced()) {
                refreshAdvanced(player, inventory, block);
                return;
            }
        }

        ItemStack filler = fillerPane();
        for (int slot = 9; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        boolean playing = info != null;

        inventory.setItem(SLOT_INFO, playing
                ? buildInfoItem(player, info) : buildIdleItem(player, block));
        inventory.setItem(SLOT_QUEUE, may(player, Action.QUEUE_OPEN)
                ? buildQueueButton(player) : filler);

        boolean seekable = may(player, Action.PLAYER_SEEK);
        inventory.setItem(SLOT_SEEK_BACK, !seekable ? filler
                : playing ? buildSeekItem(player, false) : inert(player));
        inventory.setItem(SLOT_SEEK_FORWARD, !seekable ? filler
                : playing ? buildSeekItem(player, true) : inert(player));

        inventory.setItem(SLOT_PREV, mayControl(player, playing, Action.PLAYER_PREVIOUS)
                ? buildTrackNavItem(player, false) : filler);
        inventory.setItem(SLOT_PAUSE, mayControl(player, playing, Action.PLAYER_PAUSE)
                ? buildPauseItem(player, info) : filler);
        inventory.setItem(SLOT_NEXT, mayControl(player, playing, Action.PLAYER_NEXT)
                ? buildTrackNavItem(player, true) : filler);
        inventory.setItem(SLOT_REPEAT, may(player, Action.PLAYER_REPEAT)
                ? buildRepeatItem(player, block, info) : filler);
        inventory.setItem(SLOT_SHUFFLE, may(player, Action.PLAYER_SHUFFLE)
                ? buildShuffleItem(player, block) : filler);

        inventory.setItem(SLOT_ADVANCED, advancedItems(player, block).isEmpty()
                ? filler : buildAdvancedItem(player));
        inventory.setItem(SLOT_BROADCAST, plugin.getBroadcastManager() != null
                && plugin.getBroadcastManager().canControl(player, block) ? buildBroadcastItem(player) : filler);

        ItemStack lyrics = may(player, Action.LYRICS_TOGGLE) || may(player, Action.LYRICS_PRESET)
                ? buildLyricsItem(player, block, info) : null;
        inventory.setItem(SLOT_LYRICS, lyrics != null ? lyrics : filler);
        rememberLyrics(player, lyrics);

        inventory.setItem(SLOT_EXIT, buildExitItem(player));
    }

    private void refreshAdvanced(Player player, Inventory inventory, Block block) {
        ItemStack filler = fillerPane();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        advancedItems(player, block).forEach(inventory::setItem);
        inventory.setItem(ADV_BACK, buildBackItem(player));
        inventory.setItem(SLOT_EXIT, buildExitItem(player));
    }

    private Map<Integer, ItemStack> advancedItems(Player player, Block block) {
        Map<Integer, ItemStack> items = new java.util.LinkedHashMap<>();
        if (mayPair(player, block)) put(items, ADV_PAIR, buildPairItem(player, block));
        if (may(player, Action.PLAYER_CHANNELS)) put(items, ADV_CHANNELS, buildChannelItem(player, block));
        if (may(player, Action.PLAYER_BEACON)) put(items, ADV_BEACON, buildBeaconItem(player, block));
        if (may(player, Action.PLAYER_VOLUME)) put(items, ADV_VOLUME, buildVolumeItem(player, block));
        if (may(player, Action.PLAYER_LOCAL_VOLUME)) {
            put(items, ADV_LOCAL_VOLUME, buildLocalVolumeItem(player, block));
        }
        if (may(player, Action.QUEUE_CROSSFADE)) put(items, ADV_CROSSFADE, buildCrossfadeItem(player, block));
        if (may(player, Action.PLAYER_PORTABLE)) put(items, ADV_PORTABLE, buildPortableItem(player, block));
        if (may(player, Action.PLAYER_MESSAGES)) put(items, ADV_TRACK_MESSAGES, buildTrackMessagesItem(player));
        if (dev.valkdz.cdisc.gui.dialog.Dialogs.switchable(plugin) && may(player, Action.PLAYER_SCREEN)) {
            put(items, ADV_VIEW, buildViewItem(player));
        }
        return items;
    }

    private static void put(Map<Integer, ItemStack> items, int slot, ItemStack item) {
        if (item != null) items.put(slot, item);
    }

    private ItemStack buildAdvancedItem(Player player) {
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.advanced.name"));
        meta.setLore(List.of(plugin.getMessageManager().get(player, "gui.advanced.lore")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildBroadcastItem(Player player) {
        ItemStack item = new ItemStack(Material.LIGHTNING_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "broadcast.gui.button"));
        meta.setLore(List.of(plugin.getMessageManager().get(player, "broadcast.gui.button_lore")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildBackItem(Player player) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.advanced.back"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildCrossfadeItem(Player player, Block block) {
        int seconds = plugin.getAudioPlayerManager().crossfadeSeconds();
        DiscQueue queue = plugin.getAudioPlayerManager().getQueue(block);
        boolean on = seconds > 0 && (queue == null || queue.isCrossfade());
        ItemStack item = new ItemStack(on ? Material.AMETHYST_SHARD : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.crossfade.name"));
        List<String> lore = new ArrayList<>();
        if (seconds <= 0) {
            lore.add(plugin.getMessageManager().get(player, "gui.crossfade.server_off"));
        } else {
            lore.add(on
                    ? plugin.getMessageManager().get(player, "gui.crossfade.enabled", String.valueOf(seconds))
                    : plugin.getMessageManager().get(player, "gui.crossfade.disabled"));
            lore.add(plugin.getMessageManager().get(player, "gui.crossfade.hint"));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private boolean may(Player player, Action action) {
        return plugin.getPermissions().allows(player, action);
    }

    private boolean mayControl(Player player, boolean playing, Action action) {
        return may(player, playing ? action : Action.PLAYER_PLAY);
    }

    private boolean mayPair(Player player, Block block) {
        return may(player, plugin.getSpeakerGroupManager().groupAt(block) != null
                ? Action.PAIR_MANAGE : Action.PAIR_CREATE);
    }

    private ItemStack buildVolumeItem(Player player, Block block) {
        SpeakerSettings settings = SpeakerSettings.of(block);

        ItemStack item = lightFor(settings.volume());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.volume.name"));
        meta.setLore(List.of(
                "§7" + plugin.getMessageManager().get(player,
                        settings.isMuted() ? "gui.volume.muted" : "gui.volume.value",
                        settings.volume()),
                "",
                "§8" + plugin.getMessageManager().get(player, "gui.volume.hint")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildLocalVolumeItem(Player player, Block block) {
        if (!carries(player, block)) return null;

        int own = PlayerPrefs.localVolume(player);
        int jukebox = SpeakerSettings.of(block).volume();
        boolean following = own == PlayerPrefs.VOLUME_FOLLOWS_JUKEBOX;

        ItemStack item = lightFor(following ? jukebox : own);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.local_volume.name"));
        meta.setLore(List.of(
                "§7" + plugin.getMessageManager().get(player,
                        following ? "gui.local_volume.following" : "gui.local_volume.value",
                        following ? jukebox : own),
                "",
                "§8" + plugin.getMessageManager().get(player, "gui.local_volume.hint"),
                "§8" + plugin.getMessageManager().get(player, "gui.local_volume.reset_hint")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack lightFor(int volume) {
        ItemStack item = new ItemStack(Material.LIGHT);

        int level = Math.max(0, Math.min(15, Math.round(
                SpeakerSettings.clampVolume(volume) * 15f / SpeakerSettings.MAX_VOLUME)));

        if (item.getItemMeta() instanceof BlockDataMeta meta) {
            BlockData data = Bukkit.createBlockData(Material.LIGHT);
            if (data instanceof Levelled levelled) {
                levelled.setLevel(level);
                meta.setBlockData(levelled);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    private ItemStack buildShuffleItem(Player player, Block block) {
        boolean on = plugin.getAudioPlayerManager().isShuffle(block);
        String texture = on ? GuiHeads.SHUFFLE_ON : GuiHeads.SHUFFLE_OFF;

        ItemStack item = texture.isEmpty()
                ? new ItemStack(on ? Material.LIME_DYE : Material.GRAY_DYE)
                : HeadUtils.createHead(texture);

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player,
                on ? "gui.shuffle_on.name" : "gui.shuffle_off.name"));
        meta.setLore(List.of(plugin.getMessageManager().get(player,
                on ? "gui.shuffle_on.lore" : "gui.shuffle_off.lore")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildIdleItem(Player player, Block block) {
        DiscQueue queue = plugin.getAudioPlayerManager().getQueue(block);
        int waiting = queue == null ? 0 : queue.filledCount();

        ItemStack item = new ItemStack(Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§7" + plugin.getMessageManager()
                    .get(player, "gui.info.idle"));
            meta.setLore(List.of("§8" + plugin.getMessageManager()
                    .get(player, "gui.info.idle_lore", waiting)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack inert(Player player) {
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§8" + plugin.getMessageManager()
                    .get(player, "gui.info.idle_button"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void rememberLyrics(Player player, ItemStack item) {
        if (item == null) {
            lastLyrics.remove(player.getUniqueId());
            return;
        }
        ItemMeta meta = item.getItemMeta();
        lastLyrics.put(player.getUniqueId(),
                meta == null || meta.getLore() == null ? "" : String.join("\n", meta.getLore()));
    }

    private ItemStack buildLyricsItem(Player player, Block block, PlaybackManager.PlaybackInfo info) {
        if (!plugin.cdiscConfig().isLyricsEnabled()) return null;

        LyricsMode current = LyricsPrefs.mode(player);

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.lyrics.name"));

        List<String> lore = new ArrayList<>();
        lore.add(plugin.getMessageManager().get(player, "gui.lyrics.mode_title"));
        for (LyricsMode mode : LyricsMode.values()) {
            lore.add((mode == current ? "§a▸ " : "§8  ")
                    + plugin.getMessageManager().get(player, mode.messageKey()));
        }
        lore.add(plugin.getMessageManager().get(player, "gui.lyrics.click_views"));
        lore.add(plugin.getMessageManager().get(player, "gui.lyrics.click_look"));

        lore.addAll(lyricsLore(player, info));

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private List<String> lyricsLore(Player player, PlaybackManager.PlaybackInfo info) {
        if (!plugin.cdiscConfig().isLyricsInGui()) return List.of();

        if (info == null) return List.of();

        Config config = plugin.cdiscConfig();
        LyricsRenderer.Options options = new LyricsRenderer.Options(
                config.getLyricsGuiLinesBefore(),
                config.getLyricsGuiLinesAfter(),
                LyricsStyle.of(config.getLyricsCurrentColor(), config.getLyricsOtherColor()),
                config.isLyricsCountdownEnabled() ? config.getLyricsCountdownSeconds() * 1000L : 0L,
                config.getLyricsCountdownFilled(),
                config.getLyricsCountdownEmpty(),
                1f);

        List<String> window;
        if (info.live()) {
            ChatFeed.Snapshot chat = plugin.getLiveChat() == null ? null
                    : plugin.getLiveChat().read(info.uri());
            if (chat == null || chat.messages().isEmpty()) {
                String key = chat == null ? "gui.lyrics.live" : "gui.lyrics.chat_waiting";
                return List.of("", plugin.getMessageManager().get(player, key));
            }
            window = LyricsRenderer.chat(chat.messages(), options, 255);
        } else {
            LyricsService service = plugin.getLyricsService();
            if (service == null) return List.of();

            LyricsService.Result result = service.lookup(info);

            if (!result.isFound()) {
                String key = result.state() == LyricsService.State.SEARCHING
                        ? "gui.lyrics.searching"
                        : "gui.lyrics.not_found";
                return List.of("", plugin.getMessageManager().get(player, key));
            }
            window = LyricsRenderer.window(result.lyrics(), info.position(), options);
        }
        if (window.isEmpty()) return List.of();

        List<String> lore = new ArrayList<>(window.size() + 1);
        lore.add("");
        lore.addAll(window);
        return lore;
    }

    private ItemStack buildTrackMessagesItem(Player player) {
        boolean on = PlayerPrefs.showsTrackMessages(player);

        ItemStack item = new ItemStack(Material.BELL);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.track_messages.name"));
        meta.setLore(List.of(plugin.getMessageManager().get(player,
                on ? "gui.track_messages.shown" : "gui.track_messages.hidden")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPairItem(Player player, Block block) {
        if (!plugin.cdiscConfig().isSpeakerGroupEnabled()) return null;

        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(block);
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        List<String> lore = new ArrayList<>();
        if (group == null) {
            meta.setDisplayName(plugin.getMessageManager().get(player, "gui.pair.button.unpaired"));
            lore.add(plugin.getMessageManager().get(player, "gui.pair.button.unpaired_hint"));
        } else {
            meta.setDisplayName(plugin.getMessageManager().get(player, "gui.pair.button.paired", group.name()));
            lore.add(plugin.getMessageManager().get(player,
                    group.isMain(block.getLocation()) ? "gui.pair.role_main" : "gui.pair.role_speaker"));
            lore.add(plugin.getMessageManager().get(player, "gui.pair.button.members",
                    String.valueOf(group.size())));
            lore.add(plugin.getMessageManager().get(player, "gui.pair.button.paired_hint"));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildChannelItem(Player player, Block block) {
        if (plugin.getSpeakerGroupManager().groupAt(block) != null) return null;

        SpeakerSettings settings = SpeakerSettings.of(block);

        ItemStack item = new ItemStack(Material.BREWING_STAND);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.channels.name"));

        List<String> lore = new ArrayList<>();
        lore.add(plugin.getMessageManager().get(player, "gui.pair.member.channel",
                plugin.getMessageManager().get(player,
                        "gui.channels.mode." + settings.channel().key())));
        lore.add(settings.volume() <= SpeakerSettings.MUTED
                ? plugin.getMessageManager().get(player, "gui.speaker.muted")
                : plugin.getMessageManager().get(player, "gui.pair.member.volume",
                        String.valueOf(settings.volume())));
        lore.add(plugin.getMessageManager().get(player, "gui.channels.hint"));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPortableItem(Player player, Block block) {
        if (!plugin.getPortableJukeboxManager().isEnabled()) return null;

        boolean paired = plugin.getSpeakerGroupManager().groupAt(block) != null;
        boolean room = plugin.getPortableJukeboxManager().hasRoom(player);
        boolean allowed = room && !paired;

        ItemStack item = new ItemStack(allowed ? Material.NOTE_BLOCK : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.portable.name"));
        String lore = paired ? "gui.portable.paired" : (room ? "gui.portable.lore" : "gui.portable.no_room");
        meta.setLore(List.of(plugin.getMessageManager().get(player, lore)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildViewItem(Player player) {
        ItemStack item = new ItemStack(Material.PAINTING);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.view.to_dialog"));
        meta.setLore(List.of(
                plugin.getMessageManager().get(player, "gui.view.to_dialog_lore")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack fillerPane() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildTrackNavItem(Player player, boolean forward) {
        String texture = forward ? GuiHeads.NEXT_TRACK : GuiHeads.PREV_TRACK;
        ItemStack item = HeadUtils.createHead(texture);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player,
                forward ? "gui.next_track.name" : "gui.prev_track.name"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildQueueButton(Player player) {
        ItemStack item = GuiHeads.QUEUE.isEmpty()
                ? new ItemStack(Material.PAPER)
                : HeadUtils.createHead(GuiHeads.QUEUE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.queue_open.name"));
        meta.setLore(List.of(plugin.getMessageManager().get(player, "gui.queue_open.lore")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildBeaconItem(Player player, Block block) {
        int tier = BeaconUtils.beaconTierBelow(block);
        int maxLevel = BeaconUtils.maxRangeLevel(tier);
        if (maxLevel < 1) return null;

        PlaybackManager apm = plugin.getAudioPlayerManager();
        int level = apm.getBeaconRangeLevel(block);
        if (level > maxLevel) {
            apm.setBeaconRangeLevel(block, maxLevel);
            level = maxLevel;
        }

        ItemStack item = new ItemStack(Material.BEACON);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.beacon.name"));

        int boost = plugin.cdiscConfig().getBeaconRangeBoost(level);
        int range = (int) apm.effectiveDistance(block);

        List<String> lore = new ArrayList<>();
        if (level <= 0) {
            lore.add(plugin.getMessageManager().get(player, "gui.beacon.lore_off"));
        } else {
            lore.add(plugin.getMessageManager().get(player, "gui.beacon.lore_level",
                    String.valueOf(level), String.valueOf(maxLevel), "+" + boost));
        }
        lore.add(plugin.getMessageManager().get(player, "gui.beacon.lore_range", String.valueOf(range)));
        lore.add(plugin.getMessageManager().get(player, "gui.beacon.lore_hint"));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    public void enterChatSeekMode(Player player, Block block) {
        awaitingSeekChat.put(player.getUniqueId(), block);
        player.sendMessage(plugin.getMessageManager().get(player, "gui.seek.chat_prompt"));
    }

    public Block getAwaitingSeekChat(Player player) {
        return awaitingSeekChat.get(player.getUniqueId());
    }

    public void exitChatSeekMode(Player player) {
        awaitingSeekChat.remove(player.getUniqueId());
    }

    private ItemStack buildInfoItem(Player player, PlaybackManager.PlaybackInfo info) {
        ItemStack item = HeadUtils.createHead(GuiHeads.INFO);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.info.name"));

        List<String> lore = new ArrayList<>();
        lore.add(plugin.getMessageManager().track(player, "gui.info.lore_track", 0, info.author(), info.title()));
        lore.add(plugin.getMessageManager().get(player, info.paused() ? "gui.info.lore_paused" : "gui.info.lore_playing"));

        lore.add(plugin.getMessageManager().get(player,
                info.live() ? "gui.info.lore_live" : "gui.info.lore_seek_hint"));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildSeekItem(Player player, boolean forward) {
        ItemStack item = HeadUtils.createHead(forward ? GuiHeads.SEEK_FORWARD : GuiHeads.SEEK_BACK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, forward ? "gui.seek_forward.name" : "gui.seek_back.name"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPauseItem(Player player, PlaybackManager.PlaybackInfo info) {

        boolean stopped = info == null || info.paused();

        ItemStack item = HeadUtils.createHead(stopped ? GuiHeads.PLAY : GuiHeads.PAUSE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String nameKey = stopped ? "gui.play.name" : "gui.pause.name";
        meta.setDisplayName(plugin.getMessageManager().get(player, nameKey));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildRepeatItem(Player player, Block block,
                                      PlaybackManager.PlaybackInfo info) {

        RepeatMode mode = info != null ? info.repeatMode()
                : plugin.getAudioPlayerManager().getRepeatMode(block);
        String texture = switch (mode) {
            case OFF -> GuiHeads.REPEAT_OFF;
            case QUEUE -> GuiHeads.REPEAT_ON;
            case TRACK -> GuiHeads.REPEAT_TRACK.isEmpty() ? GuiHeads.REPEAT_ON : GuiHeads.REPEAT_TRACK;
        };
        ItemStack item = HeadUtils.createHead(texture);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String nameKey = switch (mode) {
            case OFF -> "gui.repeat_off.name";
            case QUEUE -> "gui.repeat_queue.name";
            case TRACK -> "gui.repeat_track.name";
        };
        meta.setDisplayName(plugin.getMessageManager().get(player, nameKey));

        if (mode != RepeatMode.OFF) {
            meta.setLore(List.of(plugin.getMessageManager().get(player,
                    mode == RepeatMode.TRACK ? "gui.repeat_track.lore" : "gui.repeat_queue.lore")));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildExitItem(Player player) {
        ItemStack item = HeadUtils.createHead(GuiHeads.EXIT);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.exit.name"));
        item.setItemMeta(meta);
        return item;
    }

    public static long seekStepMs() {
        return SEEK_STEP_MS;
    }
}
