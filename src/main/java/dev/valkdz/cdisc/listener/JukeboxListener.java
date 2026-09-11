package dev.valkdz.cdisc.listener;

import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.AudioSession;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.net.WorldEventPacketInterceptor;
import dev.valkdz.cdisc.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import dev.valkdz.cdisc.audio.queue.DiscQueue;
import dev.valkdz.cdisc.util.DiscStorage;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class JukeboxListener implements Listener {

    private static final int EFFECT_RECORD_START = 1010;
    private static final int EFFECT_RECORD_STOP = 1011;

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private final Set<BlockKey> customDiscBlocks = ConcurrentHashMap.newKeySet();

    // Jukeboxes whose sound CDisc owns; without this the vanilla disc sound leaks
    // through. Cleared only when playback genuinely ends.
    private final Set<BlockKey> queueControlled = ConcurrentHashMap.newKeySet();

    private static final String[] TICKS_SINCE_SONG_TAG_CANDIDATES = {
            "ticks_since_song_started",
            "RecordStartTick"
    };

    private final Main plugin;

    public JukeboxListener(Main plugin) {
        this.plugin = plugin;
        initPacketListener();
    }

    private void initPacketListener() {
        WorldEventPacketInterceptor interceptor = new WorldEventPacketInterceptor(
                plugin,
                this::onWorldEventDecoded,
                () -> {
                    plugin.getLogger().severe("[CDisc] This server's Minecraft/software version is not supported "
                            + "by CDisc's packet hook (could not locate the player network channel via reflection). "
                            + "Disabling the plugin.");
                    plugin.disablePlugin();
                }
        );

        interceptor.register();
    }

    private boolean onWorldEventDecoded(org.bukkit.entity.Player player, WorldEventPacketInterceptor.WorldEventPacket event) {
        if (event.effectId() != EFFECT_RECORD_START && event.effectId() != EFFECT_RECORD_STOP) return false;

        BlockKey key = new BlockKey(player.getWorld().getUID(), event.x(), event.y(), event.z());
        boolean isCustom = customDiscBlocks.contains(key) || queueControlled.contains(key);

        Bukkit.getScheduler().runTask(plugin, () -> handleWorldEvent(player, event));

        return isCustom;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWatchingPlayerInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = e.getPlayer();
        if (!plugin.getTrackProgressDisplay().isWatching(player)) return;

        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;
        if (!(block.getState() instanceof Jukebox jukebox) || !jukebox.hasRecord()) return;
        if (!ItemUtils.isCdiscDisc(jukebox.getRecord())) return;

        e.setCancelled(true);

        if (plugin.getAudioPlayerManager().queueSize(block) > 1) {
            plugin.getQueueGuiManager().open(player, block);
        } else {
            plugin.getPlayerGuiManager().open(player, block);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIdleQueueOpen(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player player = e.getPlayer();
        if (!player.isSneaking()) return;

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held != null && !held.getType().isAir()) return;

        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        if (apm.hasActiveSession(block)) return;

        e.setCancelled(true);
        plugin.getQueueGuiManager().open(player, block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJukeboxInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;
        if (!(block.getState() instanceof Jukebox jukebox)) return;
        if (jukebox.hasRecord()) return;

        ItemStack hand = e.getItem();
        if (ItemUtils.isCdiscDisc(hand)) {
            customDiscBlocks.add(BlockKey.of(block));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDiscMovedIn(InventoryMoveItemEvent e) {
        if (!ItemUtils.isCdiscDisc(e.getItem())) return;
        if (!(e.getDestination().getHolder() instanceof Jukebox jukebox)) return;

        Block block = jukebox.getBlock();
        customDiscBlocks.add(BlockKey.of(block));

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getType() == Material.JUKEBOX
                    && block.getState() instanceof Jukebox after
                    && ItemUtils.isCdiscDisc(after.getRecord())) {
                return;
            }
            customDiscBlocks.remove(BlockKey.of(block));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDiscMovedOut(InventoryMoveItemEvent e) {
        if (!ItemUtils.isCdiscDisc(e.getItem())) return;
        if (!(e.getSource().getHolder() instanceof Jukebox jukebox)) return;

        Block block = jukebox.getBlock();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getType() != Material.JUKEBOX) return;
            if (block.getState() instanceof Jukebox after && after.hasRecord()) return;

            customDiscBlocks.remove(BlockKey.of(block));
            plugin.getAudioPlayerManager().handlePhysicalEject(block);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJukeboxEject(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (plugin.getTrackProgressDisplay().isWatching(e.getPlayer())) return;

        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;
        if (!(block.getState() instanceof Jukebox jukebox) || !jukebox.hasRecord()) return;
        if (!ItemUtils.isCdiscDisc(jukebox.getRecord())) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getType() != Material.JUKEBOX) return;
            if (block.getState() instanceof Jukebox after && after.hasRecord()) return;
            plugin.getAudioPlayerManager().handlePhysicalEject(block);
        });
    }

    private void handleWorldEvent(org.bukkit.entity.Player player, WorldEventPacketInterceptor.WorldEventPacket event) {
        try {
            Location loc = new Location(player.getWorld(), event.x(), event.y(), event.z());
            Block block = loc.getBlock();
            BlockState state = block.getState();
            if (!(state instanceof Jukebox jukebox)) return;

            if (event.effectId() == EFFECT_RECORD_START) {
                ItemStack record = jukebox.getRecord();
                boolean isCustom = ItemUtils.isCdiscDisc(record);

                if (isCustom) {
                    LavaPlayerManager apm = plugin.getAudioPlayerManager();

                    if (!apm.getSessions(jukebox.getBlock()).isEmpty()) {
                        return;
                    }

                    if (!jukebox.hasRecord()) {
                        apm.stopPlaying(jukebox.getBlock(), apm.getGeneration(jukebox.getBlock()));
                        return;
                    }

                    ItemStack r2 = jukebox.getRecord();
                    ItemMeta m2 = r2.getItemMeta();
                    if (m2 == null) {
                        apm.stopPlaying(jukebox.getBlock(), apm.getGeneration(jukebox.getBlock()));
                        return;
                    }

                    PersistentDataContainer pdc = m2.getPersistentDataContainer();
                    NamespacedKey urlKey = new NamespacedKey("cdisc", "cdisc_url");
                    NamespacedKey fallbackUrlKey = new NamespacedKey("cdisc", "cdisc_fallback_url");
                    NamespacedKey titleKey = new NamespacedKey("cdisc", "cdisc_title");
                    NamespacedKey authorKey = new NamespacedKey("cdisc", "cdisc_author");
                    NamespacedKey musicFetchKey = new NamespacedKey("cdisc", "music_fetch");
                    String query = pdc.get(urlKey, PersistentDataType.STRING);
                    String fallbackQuery = pdc.get(fallbackUrlKey, PersistentDataType.STRING);
                    String discTitle = pdc.get(titleKey, PersistentDataType.STRING);
                    String discAuthor = pdc.get(authorKey, PersistentDataType.STRING);
                    String musicFetch = pdc.get(musicFetchKey, PersistentDataType.STRING);

                    if (query == null) {
                        apm.stopPlaying(jukebox.getBlock(), apm.getGeneration(jukebox.getBlock()));
                        return;
                    }

                    apm.seedQueue(jukebox.getBlock(), jukebox.getRecord().clone());
                    apm.startPlaying(jukebox.getBlock(), query, fallbackQuery, discTitle, discAuthor, musicFetch);
                }
            }

            if (event.effectId() == EFFECT_RECORD_STOP) {
                BlockKey key = BlockKey.of(jukebox.getBlock());
                boolean hasRecord = jukebox.hasRecord();

                if (!hasRecord) {
                    customDiscBlocks.remove(key);
                    LavaPlayerManager apm = plugin.getAudioPlayerManager();
                    apm.stopPlaying(jukebox.getBlock(), apm.getGeneration(jukebox.getBlock()));
                    return;
                }

                ItemStack r2 = jukebox.getRecord();
                ItemMeta m2 = r2.getItemMeta();
                boolean isCustom2 = ItemUtils.isCdiscDisc(r2, m2);

                if (!isCustom2) {

                    customDiscBlocks.remove(key);
                    return;
                }

                List<AudioSession> sessions = plugin.getAudioPlayerManager().getSessions(jukebox.getBlock());
                boolean finished = sessions.isEmpty() || sessions.stream().allMatch(s -> s.getPlayer().getPlayingTrack() == null);

                if (finished) {

                    customDiscBlocks.remove(key);
                } else {

                    resumeSpinAnimation(state);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resumeSpinAnimation(BlockState state) {
        for (String tag : TICKS_SINCE_SONG_TAG_CANDIDATES) {
            try {
                Consumer<ReadWriteNBT> modifier = n -> n.setLong(tag, 0L);
                NBT.modify(state, modifier);
                return;
            } catch (Exception ignored) {

            }
        }
        plugin.getLogger().warning("[CDisc] Could not find a known jukebox animation NBT tag on this server version; "
                + "the spinning animation may not resume correctly, but playback itself is unaffected.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() == Material.JUKEBOX) {
            clearJukeboxNBT(e.getBlock());
            LavaPlayerManager apm = plugin.getAudioPlayerManager();
            apm.stopPlaying(e.getBlock(), apm.getGeneration(e.getBlock()));
            if (packQueueIntoDroppedJukebox(e.getBlock(), e.getPlayer())) {

                e.setDropItems(false);
            }
            customDiscBlocks.remove(BlockKey.of(e.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (e.getBlock().getType() != Material.JUKEBOX) return;
        clearJukeboxNBT(e.getBlock());
        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        apm.stopPlaying(e.getBlock(), apm.getGeneration(e.getBlock()));
        packQueueIntoDroppedJukebox(e.getBlock(), null);
        customDiscBlocks.remove(BlockKey.of(e.getBlock()));
    }

    private boolean packQueueIntoDroppedJukebox(Block block, Player breaker) {
        List<ItemStack> discs = plugin.getAudioPlayerManager().drainQueue(block);
        if (discs.isEmpty()) return false;

        clearPhysicalRecord(block);

        ItemStack jukebox = new ItemStack(Material.JUKEBOX);
        DiscStorage.store(jukebox, discs);
        describeContents(jukebox, discs.size(), breaker);

        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), jukebox);
        return true;
    }

    private void describeContents(ItemStack jukebox, int count, Player breaker) {
        ItemMeta meta = jukebox.getItemMeta();
        if (meta == null) return;

        meta.setLore(List.of(plugin.getMessageManager().get(breaker, "jukebox.stored_discs", count)));
        jukebox.setItemMeta(meta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlock().getType() != Material.JUKEBOX) return;

        List<ItemStack> discs = DiscStorage.read(e.getItemInHand());
        if (discs.isEmpty()) return;

        DiscQueue queue = plugin.getAudioPlayerManager().getOrCreateQueue(e.getBlock());
        for (ItemStack disc : discs) {
            int free = queue.firstEmpty();
            if (free < 0) {

                e.getBlock().getWorld().dropItemNaturally(
                        e.getBlock().getLocation().add(0.5, 1.0, 0.5), disc);
                continue;
            }
            queue.setSlot(free, disc);
        }

        clearStoredDiscs(e.getBlock());
    }

    private void clearStoredDiscs(Block block) {
        if (!(block.getState() instanceof org.bukkit.block.TileState state)) return;
        if (!state.getPersistentDataContainer().has(
                DiscStorage.STORED_DISCS_KEY, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY)) {
            return;
        }
        state.getPersistentDataContainer().remove(DiscStorage.STORED_DISCS_KEY);
        state.update(true, false);
    }

    public void markCustomDisc(Block block) {
        customDiscBlocks.add(BlockKey.of(block));
    }

    public void swapDiscVisual(Block block, ItemStack disc) {
        try {
            if (!(block.getState() instanceof Jukebox jukebox)) return;

            queueControlled.add(BlockKey.of(block));
            jukebox.setRecord(disc == null ? null : disc.clone());
            jukebox.update(true, false);
            if (disc != null) {
                resumeSpinAnimation(block.getState());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CDisc] Failed to swap jukebox disc visual: " + e.getMessage());
        }
    }

    public void clearControlled(Block block) {
        BlockKey key = BlockKey.of(block);
        queueControlled.remove(key);
        customDiscBlocks.remove(key);
    }

    public void clearPhysicalRecord(Block block) {
        try {
            if (block.getState() instanceof Jukebox jukebox && jukebox.hasRecord()) {
                jukebox.setRecord(null);
                jukebox.update(true, false);
            }
        } catch (Exception ignored) {
        }
    }

    @EventHandler
    public void onExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(block -> {
            if (block.getType() == Material.JUKEBOX) {
                clearJukeboxNBT(block);
                LavaPlayerManager apm = plugin.getAudioPlayerManager();

                apm.stopPlaying(block, apm.getGeneration(block));

                customDiscBlocks.remove(BlockKey.of(block));
                return true;
            }
            return false;
        });
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(block -> {
            if (block.getType() == Material.JUKEBOX) {
                clearJukeboxNBT(block);
                LavaPlayerManager apm = plugin.getAudioPlayerManager();
                apm.stopPlaying(block, apm.getGeneration(block));

                customDiscBlocks.remove(BlockKey.of(block));
                return true;
            }
            return false;
        });
    }

    public void clearJukeboxNBT(Block block) {
        for (String tag : TICKS_SINCE_SONG_TAG_CANDIDATES) {
            try {
                Consumer<ReadWriteNBT> remover = nbt -> nbt.removeKey(tag);
                NBT.modify(block.getState(), remover);
            } catch (Exception ignored) {}
        }
    }
}
