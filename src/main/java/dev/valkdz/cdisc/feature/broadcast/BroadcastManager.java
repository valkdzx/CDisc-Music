package dev.valkdz.cdisc.feature.broadcast;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.api.event.PlaybackStopEvent;
import dev.valkdz.cdisc.api.event.TrackStartEvent;
import dev.valkdz.cdisc.disc.ItemUtils;
import dev.valkdz.cdisc.feature.speaker.SpeakerSettings;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import dev.valkdz.cdisc.jukebox.queue.DiscQueue;
import dev.valkdz.cdisc.permission.Perms;
import dev.valkdz.cdisc.util.Tasks;
import dev.valkdz.cdisc.voice.VoiceBackend;
import dev.valkdz.cdisc.voice.VoiceSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BroadcastManager implements Listener, MicrophoneSink {

    private static final String FILE_NAME = "broadcasts.yml";
    private static final long SELECTION_MS = 30_000L;
    private static final Particle DUST = dust();

    public record Found(BroadcastStation station, Microphone mic) {
    }

    private record Selection(Block jukebox, UUID mic, long until) {
    }

    private final Main plugin;
    private final File file;
    private final YamlConfiguration data = new YamlConfiguration();
    private final Object saveLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean savePending = new java.util.concurrent.atomic.AtomicBoolean();
    private final List<java.util.function.Consumer<UUID>> forgetters = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final Map<Block, BroadcastStation> stations = new ConcurrentHashMap<>();
    private final Map<UUID, Selection> selecting = new ConcurrentHashMap<>();

    private ScheduledExecutorService mixer;
    private Tasks.Handle timer;
    private volatile boolean tapInstalled;
    private long ticks;
    private long lastMixFailure;

    public BroadcastManager(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    public void start() {
        if (file.isFile()) {
            try {
                data.load(file);
            } catch (Exception e) {
                plugin.getLogger().warning("Couldn't read " + FILE_NAME + ": " + e.getMessage());
            }
        }

        mixer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "cdisc-broadcast-mixer");
            thread.setDaemon(true);
            return thread;
        });
        // An exception escaping a fixed-rate task cancels every later run, and every microphone with it.
        mixer.scheduleAtFixedRate(() -> {
            try {
                mix();
            } catch (Throwable t) {
                reportMixFailure(t);
            }
        }, 20, 20, TimeUnit.MILLISECONDS);
        timer = Tasks.globalTimer(plugin, this::tick, 20L, 10L);
    }

    public void shutdown() {
        if (timer != null) timer.cancel();
        if (mixer != null) mixer.shutdownNow();
        for (BroadcastStation station : List.copyOf(stations.values())) {
            stations.remove(station.jukebox());
            persist(station);
            for (Microphone mic : station.mics()) {
                closeOutputs(mic);
                Player holder = mic.holder() == null ? null : Bukkit.getPlayer(mic.holder());
                if (holder != null) takeLever(holder, mic.id());
            }
        }
        saveNow();
    }

    public boolean available() {
        return plugin.cdiscConfig().isBroadcastEnabled() && output() != null;
    }

    private VoiceBackend output() {
        return plugin.getVoiceBackendManager() == null ? null
                : plugin.getVoiceBackendManager().getBackend();
    }

    private PlaybackManager audio() {
        return plugin.getAudioPlayerManager();
    }

    private String msg(Player player, String key, Object... args) {
        return plugin.getMessageManager().get(player, key, args);
    }

    public void createDisc(Player player, ItemStack item, String query) {
        if (!plugin.cdiscConfig().isBroadcastEnabled()) {
            player.sendMessage("§c" + msg(player, "broadcast.disabled"));
            return;
        }
        String name = BroadcastTrack.nameOf(query);
        if (name == null) {
            player.sendMessage("§c" + msg(player, "broadcast.bad_name", BroadcastTrack.MAX_NAME));
            return;
        }
        if (item.getType() == Material.GOAT_HORN) {
            player.sendMessage("§c" + msg(player, "broadcast.not_horn"));
            return;
        }

        dev.valkdz.cdisc.api.event.DiscCreateEvent event = new dev.valkdz.cdisc.api.event.DiscCreateEvent(
                player, item, BroadcastTrack.PREFIX + name, name, player.getName(), -1L);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        ItemUtils.saveTrackToDisc(item, BroadcastTrack.PREFIX + name, null, name, player.getName(), null, null);
        MicrophoneItems.setHost(item, player.getUniqueId());
        player.sendMessage("§a" + msg(player, "broadcast.disc_created", name));
    }

    public BroadcastStation station(Block jukebox) {
        return jukebox == null ? null : stations.get(jukebox);
    }

    public boolean canControl(Player player, BroadcastStation station) {
        return station != null && (Perms.isAdmin(player) || player.getUniqueId().equals(station.host()));
    }

    public boolean canControl(Player player, Block jukebox) {
        return canControl(player, station(jukebox));
    }

    public Found find(UUID micId) {
        if (micId == null) return null;
        for (BroadcastStation station : stations.values()) {
            Microphone mic = station.mic(micId);
            if (mic != null) return new Found(station, mic);
        }
        return null;
    }

    public boolean isLive(BroadcastStation station, Microphone mic) {
        return station != null && stations.get(station.jukebox()) == station && station.mics().contains(mic);
    }

    @EventHandler
    public void onTrackStart(TrackStartEvent event) {
        Block jukebox = event.getJukebox();
        if (BroadcastTrack.isAddress(event.getUri())) {
            activate(jukebox, event.getTitle());
        } else {
            deactivate(jukebox);
        }
    }

    @EventHandler
    public void onPlaybackStop(PlaybackStopEvent event) {
        deactivate(event.getJukebox());
    }

    private void activate(Block jukebox, String title) {
        if (stations.containsKey(jukebox) || !available()) return;

        UUID host = hostOf(jukebox);
        String hostName = host == null ? "?" : Bukkit.getOfflinePlayer(host).getName();
        BroadcastStation station = new BroadcastStation(jukebox, title, host, hostName == null ? "?" : hostName);
        restore(station);
        stations.put(jukebox, station);
        rebuildOutputs(station, true);
    }

    private UUID hostOf(Block jukebox) {
        DiscQueue queue = audio().getQueue(jukebox);
        ItemStack disc = queue == null ? null : queue.getCurrent();
        if (disc == null && jukebox.getState() instanceof Jukebox state) disc = state.getRecord();
        return MicrophoneItems.hostOf(disc);
    }

    private void deactivate(Block jukebox) {
        BroadcastStation station = stations.remove(jukebox);
        if (station == null) return;

        persist(station);
        saveLater();
        for (Microphone mic : station.mics()) {
            closeOutputs(mic);
            revoke(mic, "broadcast.ended");
        }
    }

    public boolean createHandheld(Player player, BroadcastStation station) {
        if (!canControl(player, station) || stations.get(station.jukebox()) != station) {
            return false;
        }
        MicColor color = station.freeColor();
        if (station.full() || color == null) {
            player.sendMessage("§c" + msg(player, "broadcast.full", BroadcastStation.MAX_MICS));
            return false;
        }

        Microphone mic = new Microphone(UUID.randomUUID(), color, Microphone.Mode.HANDHELD, null, null);
        station.mics().add(mic);
        Tasks.region(plugin, station.jukebox(), () -> buildOutputs(station, mic));

        if (giveLever(player, station, mic)) {
            player.sendMessage("§a" + msg(player, "broadcast.handheld_given",
                    mic.color().chat() + MicrophoneItems.label(plugin, player, mic)));
        } else {
            player.sendMessage("§c" + msg(player, "broadcast.inventory_full"));
        }
        changed(station);
        return true;
    }

    public void beginBlockSelection(Player player, BroadcastStation station) {
        if (!canControl(player, station)) return;
        if (station.full()) {
            player.sendMessage("§c" + msg(player, "broadcast.full", BroadcastStation.MAX_MICS));
            return;
        }
        select(player, station, null);
    }

    public boolean isSelecting(Player player) {
        return selecting.containsKey(player.getUniqueId());
    }

    public void beginMoveToBlock(Player player, BroadcastStation station, Microphone mic) {
        if (!canControl(player, station) || mic.mode() != Microphone.Mode.HANDHELD) return;
        select(player, station, mic.id());
    }

    private void select(Player player, BroadcastStation station, UUID mic) {
        selecting.put(player.getUniqueId(),
                new Selection(station.jukebox(), mic, System.currentTimeMillis() + SELECTION_MS));
        player.sendMessage("§e" + msg(player, "broadcast.select_block", plugin.cdiscConfig().getBroadcastBlockRange()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSelect(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Selection selection = selecting.get(player.getUniqueId());
        if (selection == null) return;

        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR) {
            selecting.remove(player.getUniqueId());
            player.sendMessage("§7" + msg(player, "broadcast.select_cancelled"));
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;

        event.setCancelled(true);
        selecting.remove(player.getUniqueId());
        BroadcastStation station = stations.get(selection.jukebox());
        Block block = event.getClickedBlock();
        if (selection.mic() == null) {
            createBlockMic(player, station, block);
        } else {
            moveToBlock(player, station, station == null ? null : station.mic(selection.mic()), block);
        }
    }

    // A microphone block answers its host with its own screen, whatever the block is; everyone
    // else still uses the block, so a neighbour's door or chest cannot be locked this way.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMicBlockUse(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Found found = micAt(event.getClickedBlock());
        if (found == null || !canControl(event.getPlayer(), found.station())) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        plugin.getBroadcastGui().openBlock(event.getPlayer(), found.station(), found.mic());
    }

    private boolean blockAllowed(Player player, BroadcastStation station, Block block) {
        if (station == null || !canControl(player, station)) {
            player.sendMessage("§c" + msg(player, "broadcast.ended_short"));
            return false;
        }
        int range = plugin.cdiscConfig().getBroadcastBlockRange();
        Block jukebox = station.jukebox();
        if (block.equals(jukebox) || !block.getWorld().equals(jukebox.getWorld())
                || center(block).distanceSquared(center(jukebox)) > (double) range * range) {
            player.sendMessage("§c" + msg(player, "broadcast.select_too_far", range));
            return false;
        }
        if (!plugin.getRegionGuard().allows(player, block)) {
            player.sendMessage("§c" + msg(player, "broadcast.select_protected"));
            return false;
        }
        if (block.getType().isAir() || block.getType() == Material.JUKEBOX || micAt(block) != null) {
            player.sendMessage("§c" + msg(player, "broadcast.select_taken"));
            return false;
        }
        return true;
    }

    private void createBlockMic(Player player, BroadcastStation station, Block block) {
        if (!blockAllowed(player, station, block)) return;
        MicColor color = station.freeColor();
        if (station.full() || color == null) {
            player.sendMessage("§c" + msg(player, "broadcast.full", BroadcastStation.MAX_MICS));
            return;
        }

        Microphone mic = new Microphone(UUID.randomUUID(), color, Microphone.Mode.BLOCK,
                block.getLocation(), block.getType());
        station.mics().add(mic);
        Tasks.region(plugin, station.jukebox(), () -> buildOutputs(station, mic));
        player.sendMessage("§a" + msg(player, "broadcast.block_created",
                mic.color().chat() + MicrophoneItems.label(plugin, player, mic)));
        changed(station);
    }

    private void moveToBlock(Player player, BroadcastStation station, Microphone mic, Block block) {
        if (mic == null || mic.mode() != Microphone.Mode.HANDHELD) {
            player.sendMessage("§c" + msg(player, "broadcast.ended_short"));
            return;
        }
        if (!blockAllowed(player, station, block)) return;

        revoke(mic, null);
        mic.becomeBlock(block.getLocation(), block.getType());
        player.sendMessage("§a" + msg(player, "broadcast.block_created",
                mic.color().chat() + MicrophoneItems.label(plugin, player, mic)));
        changed(station);
    }

    public void makeHandheld(Player player, BroadcastStation station, Microphone mic) {
        if (!canControl(player, station) || mic.mode() != Microphone.Mode.BLOCK || !isLive(station, mic)) return;
        mic.becomeHandheld();
        changed(station);
        takeFor(player, station, mic);
    }

    private Found micAt(Block block) {
        if (block == null) return null;
        for (BroadcastStation station : stations.values()) {
            for (Microphone mic : station.mics()) {
                if (mic.mode() == Microphone.Mode.BLOCK && sameBlock(mic.block(), block)) return new Found(station, mic);
            }
        }
        return null;
    }

    public void removeMic(BroadcastStation station, Microphone mic) {
        if (!station.mics().remove(mic)) return;
        closeOutputs(mic);
        revoke(mic, "broadcast.removed_holder");
        changed(station);
    }

    public void setVolume(BroadcastStation station, Microphone mic, int volume) {
        mic.setVolume(volume);
        changed(station);
    }

    public void toggleMute(BroadcastStation station, Microphone mic) {
        mic.setMuted(!mic.muted());
        changed(station);
    }

    public void takeFor(Player player, BroadcastStation station, Microphone mic) {
        if (!canControl(player, station) || mic.mode() != Microphone.Mode.HANDHELD || mic.holder() != null) return;
        if (giveLever(player, station, mic)) {
            player.sendMessage("§a" + msg(player, "broadcast.handheld_given",
                    mic.color().chat() + MicrophoneItems.label(plugin, player, mic)));
        } else {
            player.sendMessage("§c" + msg(player, "broadcast.inventory_full"));
        }
    }

    public void forceReturn(BroadcastStation station, Microphone mic) {
        returnToHost(station, mic, "broadcast.taken_away");
    }

    public void returnToJukebox(Player player, BroadcastStation station, Microphone mic) {
        if (!player.getUniqueId().equals(mic.holder()) && !canControl(player, station)) return;
        revoke(mic, "broadcast.returned");
    }

    public List<Player> passTargets(Player from, BroadcastStation station) {
        List<Player> out = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.equals(from) || !from.canSee(player) || !inRange(player, station)) continue;
            out.add(player);
        }
        return out;
    }

    public void pass(Player from, BroadcastStation station, Microphone mic, Player to) {
        if (!from.getUniqueId().equals(mic.holder()) || !isLive(station, mic)) return;
        if (!to.isOnline() || !inRange(to, station)) {
            from.sendMessage("§c" + msg(from, "broadcast.pass_gone"));
            return;
        }

        Tasks.onEntity(plugin, to, () -> {
            if (!from.getUniqueId().equals(mic.holder()) || !isLive(station, mic)) return;
            if (!giveLever(to, station, mic)) {
                Tasks.onEntity(plugin, from, () -> from.sendMessage("§c" + msg(from, "broadcast.pass_full", to.getName())));
                return;
            }
            String label = mic.color().chat() + MicrophoneItems.label(plugin, to, mic);
            to.sendMessage("§a" + msg(to, "broadcast.received", label, from.getName()));
            Tasks.onEntity(plugin, from, () -> {
                takeLever(from, mic.id());
                from.sendMessage("§a" + msg(from, "broadcast.passed",
                        mic.color().chat() + MicrophoneItems.label(plugin, from, mic), to.getName()));
            });
        });
    }

    private void returnToHost(BroadcastStation station, Microphone mic, String reasonKey) {
        UUID previous = mic.holder();
        revoke(mic, reasonKey);
        if (!isLive(station, mic) || station.host() == null) return;

        Player host = Bukkit.getPlayer(station.host());
        if (host == null || host.getUniqueId().equals(previous)) return;

        Tasks.onEntity(plugin, host, () -> {
            if (!isLive(station, mic) || mic.holder() != null || !inRange(host, station)) return;
            if (giveLever(host, station, mic)) {
                host.sendMessage("§a" + msg(host, "broadcast.came_back",
                        mic.color().chat() + MicrophoneItems.label(plugin, host, mic)));
            }
        });
    }

    private void revoke(Microphone mic, String reasonKey) {
        UUID previous = mic.holder();
        mic.setHolder(null);
        if (previous == null) return;

        Player holder = Bukkit.getPlayer(previous);
        if (holder == null) return;
        Tasks.onEntity(plugin, holder, () -> {
            takeLever(holder, mic.id());
            if (reasonKey != null) {
                holder.sendMessage("§e" + msg(holder, reasonKey,
                        mic.color().chat() + MicrophoneItems.label(plugin, holder, mic)));
            }
        });
    }

    private boolean giveLever(Player player, BroadcastStation station, Microphone mic) {
        ItemStack lever = MicrophoneItems.create(plugin, player, mic, station.name());
        if (!player.getInventory().addItem(lever).isEmpty()) {
            takeLever(player, mic.id());
            return false;
        }
        mic.setHolder(player.getUniqueId());
        return true;
    }

    public void takeLever(Player player, UUID micId) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (micId.equals(MicrophoneItems.micIdOf(inventory.getItem(slot)))) inventory.setItem(slot, null);
        }
        if (micId.equals(MicrophoneItems.micIdOf(player.getItemOnCursor()))) player.setItemOnCursor(null);
    }

    // A lever is only good while it names a live microphone that this player holds: every
    // other copy, from a restart, a dupe or a return while offline, is taken away.
    public void validateLevers(Player player) {
        Set<UUID> seen = new HashSet<>();
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            UUID id = MicrophoneItems.micIdOf(inventory.getItem(slot));
            if (id != null && !(holds(player, id) && seen.add(id))) inventory.setItem(slot, null);
        }
        UUID cursor = MicrophoneItems.micIdOf(player.getItemOnCursor());
        if (cursor != null && !(holds(player, cursor) && seen.add(cursor))) player.setItemOnCursor(null);
    }

    private boolean holds(Player player, UUID micId) {
        Found found = find(micId);
        return found != null && player.getUniqueId().equals(found.mic().holder());
    }

    public void holderGone(Player player) {
        for (BroadcastStation station : stations.values()) {
            for (Microphone mic : station.mics()) {
                if (!player.getUniqueId().equals(mic.holder())) continue;
                takeLever(player, mic.id());
                mic.setHolder(null);
                returnToHost(station, mic, null);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selecting.remove(event.getPlayer().getUniqueId());
        holderGone(event.getPlayer());
        UUID id = event.getPlayer().getUniqueId();
        forgetters.forEach(forget -> forget.accept(id));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Tasks.entityLater(plugin, player, () -> validateLevers(player), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJukeboxBroken(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.JUKEBOX) return;
        Tasks.regionLater(plugin, block.getLocation(), () -> {
            if (stations.containsKey(block) || block.getType() == Material.JUKEBOX) return;
            synchronized (saveLock) {
                data.set(path(block), null);
            }
            saveLater();
        }, 2L);
    }

    private boolean inRange(Player player, BroadcastStation station) {
        Location at = player.getLocation();
        Block jukebox = station.jukebox();
        if (at.getWorld() == null || !at.getWorld().equals(jukebox.getWorld())) return false;
        int range = plugin.cdiscConfig().getBroadcastHandheldRange();
        return at.distanceSquared(center(jukebox)) <= (double) range * range;
    }

    @Override
    public boolean wants(UUID player, int distance) {
        return !stations.isEmpty() && !micsFor(player, distance).isEmpty();
    }

    @Override
    public void feed(UUID player, byte[] pcm, int distance) {
        for (Pick pick : micsFor(player, distance)) pick.mic().hear(player, pcm, pick.gain());
    }

    @Override
    public void ended(UUID player) {
    }

    @Override
    public void onForget(java.util.function.Consumer<UUID> forget) {
        forgetters.add(forget);
    }

    private record Pick(Microphone mic, double gain) {
    }

    // A block microphone hears a player as far as another player standing there would,
    // and as much quieter as that player would hear them from where they stand.
    private List<Pick> micsFor(UUID id, int distance) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return List.of();

        List<Pick> out = new ArrayList<>(1);
        Location at = null;
        for (BroadcastStation station : stations.values()) {
            for (Microphone mic : station.mics()) {
                if (mic.mode() == Microphone.Mode.HANDHELD) {
                    if (id.equals(mic.holder())) out.add(new Pick(mic, 1.0D));
                    continue;
                }
                if (at == null) at = player.getEyeLocation();
                Location block = mic.block();
                if (block == null || block.getWorld() == null || !block.getWorld().equals(at.getWorld())) continue;
                double away = at.distance(block.add(0.5, 0.5, 0.5));
                if (away < distance) out.add(new Pick(mic, 1.0D - away / distance));
            }
        }
        return out;
    }

    private void mix() {
        for (BroadcastStation station : stations.values()) {
            boolean paused = audio().isPaused(station.jukebox());
            for (Microphone mic : station.mics()) {
                byte[] frame = mic.mix();
                Set<UUID> talkers = mic.talkers();
                if (mic.excludedChanged(talkers)) {
                    for (Microphone.Output output : mic.outputs()) output.session().setSilencedListeners(talkers);
                }
                if (frame == null || paused || mic.muted()) continue;
                for (Microphone.Output output : mic.outputs()) output.session().sendFrame(frame);
            }
        }
    }

    private void reportMixFailure(Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastMixFailure < 60_000L) return;
        lastMixFailure = now;
        plugin.getLogger().log(java.util.logging.Level.WARNING, "[CDisc] A broadcast frame could not be mixed", t);
    }

    private void tick() {
        if (!tapInstalled && !stations.isEmpty()) {
            tapInstalled = dev.valkdz.cdisc.voice.plasmovoice.PlasmoMicrophoneTap.install(plugin, this);
        }
        ticks++;
        if (!plugin.cdiscConfig().isBroadcastEnabled()) {
            for (BroadcastStation station : stations.values()) {
                Tasks.region(plugin, station.jukebox(), () -> deactivate(station.jukebox()));
            }
            return;
        }
        if (ticks % 4 == 0) resumeMissed();

        long now = System.currentTimeMillis();
        selecting.values().removeIf(selection -> selection.until() < now);

        for (BroadcastStation station : stations.values()) {
            Tasks.region(plugin, station.jukebox(), () -> keepUp(station));

            for (Microphone mic : station.mics()) {
                UUID holderId = mic.holder();
                if (mic.mode() != Microphone.Mode.HANDHELD || holderId == null) continue;
                Player holder = Bukkit.getPlayer(holderId);
                if (holder == null) {
                    mic.setHolder(null);
                    returnToHost(station, mic, null);
                    continue;
                }
                Tasks.onEntity(plugin, holder, () -> {
                    if (!holderId.equals(mic.holder()) || inRange(holder, station)) return;
                    holder.sendMessage("§c" + msg(holder, "broadcast.lost_range",
                            mic.color().chat() + MicrophoneItems.label(plugin, holder, mic),
                            plugin.cdiscConfig().getBroadcastHandheldRange()));
                    returnToHost(station, mic, null);
                });
            }
        }

        if (ticks % 4 == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Tasks.onEntity(plugin, player, () -> validateLevers(player));
            }
        }
    }


    private void resumeMissed() {
        for (Block jukebox : audio().activeBlocks()) {
            if (stations.containsKey(jukebox)) continue;
            PlaybackManager.PlaybackInfo info = audio().getPlaybackInfo(jukebox);
            if (info == null || !BroadcastTrack.isAddress(info.uri())) continue;
            Tasks.region(plugin, jukebox, () -> activate(jukebox, info.title()));
        }
    }
    private void keepUp(BroadcastStation station) {
        if (stations.get(station.jukebox()) != station) return;
        if (!audio().hasActiveSession(station.jukebox())) {
            deactivate(station.jukebox());
            return;
        }

        rebuildOutputs(station, false);
        for (Microphone mic : station.mics()) {
            // Plasmo marks the source dirty on every apply, and a client drops audio until it has
            // asked what changed, so only a setting that really changed may be applied again.
            for (Microphone.Output output : mic.outputs()) {
                SpeakerSettings now = SpeakerSettings.of(output.at());
                if (!now.equals(output.applied().getAndSet(now))) output.session().applySpeakerSettings(now);
            }
            if (mic.mode() != Microphone.Mode.BLOCK) continue;

            Location at = mic.block();
            World world = at.getWorld();
            if (world == null || !world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            if (at.getBlock().getType() != mic.blockType()) {
                removeMic(station, mic);
                notifyHost(station, "broadcast.block_broken", mic);
                continue;
            }
            if (ticks % 2 == 0 && DUST != null) {
                world.spawnParticle(DUST, at.clone().add(0.5, 1.2, 0.5), 4, 0.15, 0.1, 0.15, 0,
                        new Particle.DustOptions(mic.color().color(), 1.2f));
            }
        }
    }

    private void notifyHost(BroadcastStation station, String key, Microphone mic) {
        Player host = station.host() == null ? null : Bukkit.getPlayer(station.host());
        if (host == null) return;
        Tasks.onEntity(plugin, host, () -> host.sendMessage("§c" + msg(host, key,
                mic.color().chat() + MicrophoneItems.label(plugin, host, mic))));
    }

    private void rebuildOutputs(BroadcastStation station, boolean force) {
        List<PlaybackManager.AudibleAnchor> anchors = audio().audibleAnchors(station.jukebox());
        List<UUID> ids = anchors.stream().map(anchor -> anchor.entity().getUniqueId()).toList();
        if (!force && ids.equals(station.anchorIds())) return;

        station.setAnchorIds(ids);
        for (Microphone mic : station.mics()) buildOutputs(station, mic, anchors);
    }

    private void buildOutputs(BroadcastStation station, Microphone mic) {
        buildOutputs(station, mic, audio().audibleAnchors(station.jukebox()));
    }

    private void buildOutputs(BroadcastStation station, Microphone mic, List<PlaybackManager.AudibleAnchor> anchors) {
        VoiceBackend backend = output();
        List<Microphone.Output> fresh = new ArrayList<>();
        if (backend != null && isLive(station, mic)) {
            float distance = audio().effectiveDistance(station.jukebox());
            for (PlaybackManager.AudibleAnchor anchor : anchors) {
                VoiceSession session = backend.createSyncedEntitySession(anchor.entity(), distance);
                if (session == null) continue;
                SpeakerSettings settings = SpeakerSettings.of(anchor.block());
                session.applySpeakerSettings(settings);
                fresh.add(new Microphone.Output(session, anchor.block(),
                        new java.util.concurrent.atomic.AtomicReference<>(settings)));
            }
        }
        for (Microphone.Output old : mic.replaceOutputs(fresh)) old.session().close();
    }

    private void closeOutputs(Microphone mic) {
        for (Microphone.Output old : mic.replaceOutputs(List.of())) old.session().close();
    }

    private void changed(BroadcastStation station) {
        persist(station);
        saveLater();
    }

    private void persist(BroadcastStation station) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Microphone mic : station.mics()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", mic.id().toString());
            entry.put("color", mic.color().name());
            entry.put("mode", mic.mode().name());
            entry.put("volume", mic.volume());
            entry.put("muted", mic.muted());
            entry.put("host", station.host() == null ? "" : station.host().toString());
            if (mic.mode() == Microphone.Mode.BLOCK) {
                Location at = mic.block();
                entry.put("world", at.getWorld() == null ? "" : at.getWorld().getUID().toString());
                entry.put("x", at.getBlockX());
                entry.put("y", at.getBlockY());
                entry.put("z", at.getBlockZ());
                entry.put("type", mic.blockType().name());
            }
            list.add(entry);
        }
        String host = station.host() == null ? "" : station.host().toString();
        synchronized (saveLock) {
            for (Map<?, ?> kept : data.getMapList(path(station.jukebox()))) {
                Object owner = kept.get("host");
                if (owner == null || host.equals(String.valueOf(owner))) continue;
                Map<String, Object> copy = new LinkedHashMap<>();
                kept.forEach((key, value) -> copy.put(String.valueOf(key), value));
                list.add(copy);
            }
            data.set(path(station.jukebox()), list.isEmpty() ? null : list);
        }
    }

    private void restore(BroadcastStation station) {
        List<Map<?, ?>> list;
        synchronized (saveLock) {
            list = data.getMapList(path(station.jukebox()));
        }
        for (Map<?, ?> entry : list) {
            try {
                // Microphones belong to the host who placed them, not to whoever plays a broadcast here next.
                Object owner = entry.get("host");
                String host = station.host() == null ? "" : station.host().toString();
                if (owner != null && !host.equals(String.valueOf(owner))) continue;
                UUID id = UUID.fromString(String.valueOf(entry.get("id")));
                MicColor color = MicColor.valueOf(String.valueOf(entry.get("color")));
                Microphone.Mode mode = Microphone.Mode.valueOf(String.valueOf(entry.get("mode")));
                Location at = null;
                Material type = null;
                if (mode == Microphone.Mode.BLOCK) {
                    World world = Bukkit.getWorld(UUID.fromString(String.valueOf(entry.get("world"))));
                    if (world == null) continue;
                    at = new Location(world, number(entry.get("x")), number(entry.get("y")), number(entry.get("z")));
                    type = Material.valueOf(String.valueOf(entry.get("type")));
                }
                if (station.full() || station.mics().stream().anyMatch(mic -> mic.color() == color)) continue;

                Microphone mic = new Microphone(id, color, mode, at, type);
                mic.setVolume(number(entry.get("volume")));
                mic.setMuted(Boolean.TRUE.equals(entry.get("muted")));
                station.mics().add(mic);
            } catch (RuntimeException ignored) {

            }
        }
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static String path(Block block) {
        return "jukeboxes." + block.getWorld().getUID() + "_" + block.getX() + "_" + block.getY() + "_" + block.getZ();
    }

    private void saveLater() {
        if (!plugin.isEnabled()) {
            saveNow();
            return;
        }
        if (savePending.compareAndSet(false, true)) {
            Tasks.asyncLater(plugin, () -> {
                savePending.set(false);
                saveNow();
            }, 20L);
        }
    }

    private void saveNow() {
        synchronized (saveLock) {
            try {
                ConfigurationSection root = data.getConfigurationSection("jukeboxes");
                if (root != null && root.getKeys(false).isEmpty()) data.set("jukeboxes", null);
                data.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't write " + FILE_NAME + ": " + e.getMessage());
            }
        }
    }

    private static Location center(Block block) {
        return block.getLocation().add(0.5, 0.5, 0.5);
    }

    private static boolean sameBlock(Location at, Block block) {
        return at != null && at.getWorld() != null && at.getWorld().equals(block.getWorld())
                && at.getBlockX() == block.getX() && at.getBlockY() == block.getY() && at.getBlockZ() == block.getZ();
    }

    private static Particle dust() {
        for (String name : new String[]{"DUST", "REDSTONE"}) {
            try {
                return Particle.valueOf(name);
            } catch (IllegalArgumentException ignored) {

            }
        }
        return null;
    }
}
