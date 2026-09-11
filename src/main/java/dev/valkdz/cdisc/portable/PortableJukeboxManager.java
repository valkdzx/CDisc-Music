package dev.valkdz.cdisc.portable;

import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.audio.queue.DiscQueue;
import dev.valkdz.cdisc.util.DiscStorage;
import dev.valkdz.cdisc.voice.anchor.SoundAnchor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

public final class PortableJukeboxManager {

    public static final NamespacedKey CARRY_ID_KEY =
            new NamespacedKey(Main.getInstance(), "cdisc_portable_id");

    public static final NamespacedKey BLOCK_NBT_KEY =
            new NamespacedKey(Main.getInstance(), "cdisc_portable_block_nbt");

    private final Main plugin;

    private final Map<UUID, List<Carry>> carries = new ConcurrentHashMap<>();

    private final Map<UUID, Integer> handleSlots = new ConcurrentHashMap<>();
    private BukkitTask particleTask;
    private BukkitTask followTask;

    public PortableJukeboxManager(Main plugin) {
        this.plugin = plugin;
    }

    public record Carry(UUID id, UUID carrier, Block origin, SoundAnchor anchor, String blockNbt) {
    }

    public void start() {
        long period = Math.max(20L, plugin.cdiscConfig().getPortableParticleTicks());
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::emitParticles, period, period);

        followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::followCarriers, 1L, 1L);
    }

    public void stop() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }

        for (Carry carry : allCarries()) {
            Player carrier = Bukkit.getPlayer(carry.carrier());
            endCarry(carry, carrier == null ? null : findHandle(carrier, carry));
        }
    }

    public boolean isEnabled() {
        return plugin.cdiscConfig().isPortableEnabled();
    }

    public Carry carryOf(Player player) {
        List<Carry> held = carries.get(player.getUniqueId());
        return held == null || held.isEmpty() ? null : held.get(held.size() - 1);
    }

    public List<Carry> carriesOf(Player player) {
        List<Carry> held = carries.get(player.getUniqueId());
        return held == null ? List.of() : List.copyOf(held);
    }

    private List<Carry> allCarries() {
        List<Carry> out = new ArrayList<>();
        for (List<Carry> held : carries.values()) out.addAll(held);
        return out;
    }

    public Carry carryById(String id) {
        if (id == null) return null;
        for (Carry carry : allCarries()) {
            if (carry.id().toString().equals(id)) return carry;
        }
        return null;
    }

    public Carry carryOfBlock(Block origin) {
        for (List<Carry> held : carries.values()) {
            for (Carry carry : held) {
                if (carry.origin().equals(origin)) return carry;
            }
        }
        return null;
    }

    public boolean isCarried(Block origin) {
        if (origin == null) return false;
        for (Carry carry : allCarries()) {
            if (origin.equals(carry.origin())) return true;
        }
        return false;
    }

    public boolean hasRoom(Player player) {
        return player.getInventory().firstEmpty() >= 0;
    }

    public boolean pickUp(Player player, Block block) {
        if (!isEnabled() || !hasRoom(player)) return false;
        if (!plugin.getPermissions().allows(player, dev.valkdz.cdisc.permission.Action.PLAYER_PORTABLE)) {
            return false;
        }
        if (carriesOf(player).size() >= plugin.cdiscConfig().getPortableMaxPerPlayer()) return false;

        if (plugin.getSpeakerGroupManager().groupAt(block) != null) return false;

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        SoundAnchor anchor = apm.getAnchor(block);
        if (anchor == null || !anchor.isAlive()) return false;

        plugin.getQueueGuiManager().forceCloseFor(block);
        plugin.getPlayerGuiManager().forceCloseFor(block);

        String blockNbt = captureNbt(block);

        UUID id = UUID.randomUUID();
        ItemStack item = buildItem(player, id, blockNbt);

        plugin.getJukeboxListener().clearPhysicalRecord(block);
        block.setType(Material.AIR, false);

        player.getInventory().addItem(item);

        anchor.setTeleportSmoothing(2);
        anchor.followAt(audioPointFor(player));

        plugin.getAudioPlayerManager().setPrivateListener(block, player.getUniqueId());

        carries.computeIfAbsent(player.getUniqueId(), k -> new CopyOnWriteArrayList<>())
                .add(new Carry(id, player.getUniqueId(), block, anchor, blockNbt));
        return true;
    }

    private Location audioPointFor(Player carrier) {
        return carrier.getLocation();
    }

    private void followCarriers() {
        if (carries.isEmpty()) return;
        for (Carry carry : allCarries()) {
            Player carrier = Bukkit.getPlayer(carry.carrier());
            if (carrier == null || !carrier.isOnline()) continue;
            endIfHandleGone(carrier, carry);

            List<Carry> held = carries.get(carry.carrier());
            if (held == null || !held.contains(carry)) continue;

            SoundAnchor anchor = carry.anchor();
            if (anchor != null && anchor.isAlive()) {
                anchor.followAt(audioPointFor(carrier));
            }
        }
    }

    public void placeBack(Carry carry, Block target) {

        List<Carry> held = carries.get(carry.carrier());
        if (held != null) {
            held.remove(carry);
            if (held.isEmpty()) carries.remove(carry.carrier());
        }
        handleSlots.remove(carry.id());

        SoundAnchor anchor = carry.anchor();
        if (anchor != null && anchor.isAlive()) {

            anchor.setTeleportSmoothing(0);
            anchor.parkAt(target.getLocation().add(0.5, 0.5, 0.5));
        }

        plugin.getJukeboxListener().markCustomDisc(target);
        restoreNbt(target, carry.blockNbt());

        if (!target.equals(carry.origin())) {
            dev.valkdz.cdisc.speaker.SpeakerSettings.store(target,
                    dev.valkdz.cdisc.speaker.SpeakerSettings.of(carry.origin()));
        } else {
            dev.valkdz.cdisc.speaker.SpeakerSettings.flush(target);
        }

        if (!target.equals(carry.origin())) {
            plugin.getAudioPlayerManager().relocateSession(carry.origin(), target);
        }

        // Must come after the relocate, or it would address the old key.
        plugin.getAudioPlayerManager().setPrivateListener(target, null);

        DiscQueue queue = plugin.getAudioPlayerManager().getQueue(target);
        ItemStack current = queue == null ? null : queue.getCurrent();
        if (current != null && plugin.getAudioPlayerManager().hasActiveSession(target)) {
            plugin.getJukeboxListener().swapDiscVisual(target, current);
            plugin.getJukeboxListener().markCustomDisc(target);
        }
    }

    public void endCarry(Carry carry, ItemStack handle) {
        settle(carry, handle, true);
    }

    public void onSessionEnded(Block origin) {
        for (Carry carry : allCarries()) {
            if (!carry.origin().equals(origin)) continue;
            Player carrier = Bukkit.getPlayer(carry.carrier());
            settle(carry, carrier == null ? null : findHandle(carrier, carry), false);
        }
    }

    private void settle(Carry carry, ItemStack handle, boolean stopPlayback) {
        List<Carry> held = carries.get(carry.carrier());
        if (held == null || !held.remove(carry)) return;
        if (held.isEmpty()) carries.remove(carry.carrier());
        handleSlots.remove(carry.id());

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        Block origin = carry.origin();

        List<ItemStack> discs = apm.drainQueue(origin);
        if (stopPlayback) {
            apm.stopPlaying(origin, apm.getGeneration(origin));
        }

        Player carrier = Bukkit.getPlayer(carry.carrier());
        if (handle != null) {
            materialize(handle, discs, carrier);
            return;
        }

        if (discs.isEmpty()) return;

        ItemStack recovered = new ItemStack(Material.JUKEBOX);
        materialize(recovered, discs, carrier);
        dropRecovered(carrier, origin, recovered);
    }

    private void dropRecovered(Player carrier, Block origin, ItemStack jukebox) {
        if (carrier != null && carrier.isOnline()) {
            carrier.getWorld().dropItemNaturally(carrier.getLocation(), jukebox);
            return;
        }
        if (origin.getWorld().isChunkLoaded(origin.getX() >> 4, origin.getZ() >> 4)) {
            origin.getWorld().dropItemNaturally(origin.getLocation().add(0.5, 0.5, 0.5), jukebox);
        } else {
            plugin.getLogger().warning("[CDisc] Couldn't return " + jukebox.getAmount()
                    + " carried jukebox to an offline carrier; its chunk isn't loaded.");
        }
    }

    private void materialize(ItemStack handle, List<ItemStack> discs, Player viewer) {
        clearHandle(handle);
        if (discs.isEmpty()) return;

        DiscStorage.store(handle, discs);
        ItemMeta meta = handle.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(plugin.getMessageManager()
                    .get(viewer, "jukebox.stored_discs", discs.size())));
            handle.setItemMeta(meta);
        }
    }

    public ItemStack findHandle(Player player, Carry carry) {
        if (player == null) return null;

        String wanted = carry.id().toString();
        Integer lastSeen = handleSlots.get(carry.id());

        // Reading the whole inventory copies every slot, and this runs every tick per
        // carrier, so the slot it was found in last time is tried on its own first.
        if (lastSeen != null) {
            ItemStack there = player.getInventory().getItem(lastSeen);
            if (wanted.equals(handleIdOf(there))) return there;
        }

        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (!wanted.equals(handleIdOf(contents[slot]))) continue;

            handleSlots.put(carry.id(), slot);
            return contents[slot];
        }

        handleSlots.remove(carry.id());
        return null;
    }

    private ItemStack findHandleIn(ItemStack[] contents, Carry carry) {
        if (contents == null) return null;
        String wanted = carry.id().toString();
        for (ItemStack item : contents) {
            if (wanted.equals(handleIdOf(item))) return item;
        }
        return null;
    }

    private void endIfHandleGone(Player carrier, Carry carry) {
        if (findHandle(carrier, carry) != null) return;

        if (carry.id().toString().equals(handleIdOf(carrier.getItemOnCursor()))) return;

        InventoryView view = carrier.getOpenInventory();
        ItemStack stashed = findHandleIn(view.getTopInventory().getContents(), carry);
        if (stashed != null) {

            if (view.getType() == InventoryType.CRAFTING) return;
            settle(carry, stashed, true);
            return;
        }

        List<ItemStack> discs = plugin.getAudioPlayerManager().drainQueue(carry.origin());
        settle(carry, null, true);
        if (discs.isEmpty()) return;

        ItemStack recovered = new ItemStack(Material.JUKEBOX);
        materialize(recovered, discs, carrier);
        carrier.getWorld().dropItemNaturally(carrier.getLocation(), recovered);
    }

    public static void clearHandle(ItemStack item) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(CARRY_ID_KEY);
        meta.getPersistentDataContainer().remove(BLOCK_NBT_KEY);
        meta.setLore(null);
        item.setItemMeta(meta);
    }

    public static String handleIdOf(ItemStack item) {
        if (item == null || item.getType() != Material.JUKEBOX) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(CARRY_ID_KEY, PersistentDataType.STRING);
    }

    public static String blockNbtOf(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(BLOCK_NBT_KEY, PersistentDataType.STRING);
    }

    private ItemStack buildItem(Player player, UUID id, String blockNbt) {
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.getPersistentDataContainer().set(CARRY_ID_KEY, PersistentDataType.STRING, id.toString());
        if (blockNbt != null) {
            meta.getPersistentDataContainer().set(BLOCK_NBT_KEY, PersistentDataType.STRING, blockNbt);
        }
        meta.setDisplayName(plugin.getMessageManager().get(player, "portable.item.name"));
        meta.setLore(List.of(plugin.getMessageManager().get(player, "portable.item.lore")));
        item.setItemMeta(meta);
        return item;
    }

    private void emitParticles() {
        if (carries.isEmpty()) return;
        for (Carry carry : allCarries()) {
            Player carrier = Bukkit.getPlayer(carry.carrier());
            if (carrier == null || !carrier.isOnline()) continue;
            carrier.getWorld().spawnParticle(Particle.NOTE,
                    carrier.getLocation().add(0, 2.2, 0), 1, 0.2, 0.1, 0.2, 1.0);
        }
    }

    private static final int MAX_SNAPSHOT_CHARS = 20_000;

    private String captureNbt(Block block) {
        try {
            BlockState state = block.getState();

            Function<ReadableNBT, String> reader = ReadableNBT::toString;
            String snapshot = NBT.get(state, reader);

            if (snapshot != null && snapshot.length() > MAX_SNAPSHOT_CHARS) {

                plugin.getLogger().warning("Jukebox NBT snapshot was "
                        + snapshot.length() + " chars, over the " + MAX_SNAPSHOT_CHARS
                        + " limit — carrying it without one. Keys: " + topLevelKeys(snapshot));
                return null;
            }
            return snapshot;
        } catch (Exception e) {
            plugin.getLogger().warning("[CDisc] Could not snapshot jukebox NBT before pickup: " + e.getMessage());
            return null;
        }
    }

    private static String topLevelKeys(String snapshot) {
        try {
            return String.join(", ", NBT.parseNBT(snapshot).getKeys());
        } catch (Exception e) {
            return "?";
        }
    }

    private void restoreNbt(Block block, String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return;
        try {
            BlockState state = block.getState();
            Consumer<ReadWriteNBT> modifier = nbt -> nbt.mergeCompound(NBT.parseNBT(snapshot));
            NBT.modify(state, modifier);
        } catch (Exception e) {
            plugin.getLogger().warning("[CDisc] Could not restore jukebox NBT on placement: " + e.getMessage()
                    + " — the jukebox works, but block data set by other plugins may be lost.");
        }
    }
}
