package dev.valkdz.cdisc.lyrics;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.util.Config;
import dev.valkdz.cdisc.util.DisplayCompat;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LyricsDisplay {

    private static final String TAG_VALUE = "cdisc-lyrics-display";

    private static final double PERSONAL_RANGE_SQUARED = 80 * 80;

    private static final float[] SIZE_SCALE = {
            0.5f, 0.6f, 0.7f, 0.85f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f
    };

    private static final float LINE_HEIGHT = 0.25f;

    private static final int SWEEP_TICKS = 20 * 60;

    private static final double SWEEP_RADIUS = 4;
    private static final double SWEEP_HEIGHT = 8;

    private final Main plugin;
    private final LyricsService service;
    private final NamespacedKey displayKey;

    private final Map<Block, Hologram> states = new HashMap<>();

    private final Set<UUID> awaiting = new HashSet<>();

    private BukkitTask task;

    private int sweepIn = SWEEP_TICKS;

    public LyricsDisplay(Main plugin, LyricsService service) {
        this.plugin = plugin;
        this.service = service;
        this.displayKey = new NamespacedKey(plugin, "cdisc_lyrics_display");
    }

    private static final class Variant {
        TextDisplay entity;

        String lastText;

        HologramStyle applied;
    }

    private static final class Hologram {

        final Variant shared = new Variant();

        final Map<UUID, Variant> personal = new HashMap<>();

        final Set<UUID> hiddenFromShared = new HashSet<>();

        String trackTitle;
        long trackDuration = Long.MIN_VALUE;

        int recheckIn;

        SyncedLyrics lyrics;

        long lastFrame = Long.MIN_VALUE;

        int lastIndex = Integer.MIN_VALUE;

        int lastFirst = Integer.MIN_VALUE;

        int fadeLeft;

        boolean slidePending;

        boolean pendingRelease;

        void resetForNewTrack() {
            lyrics = null;
            lastFrame = Long.MIN_VALUE;
            lastIndex = Integer.MIN_VALUE;
            lastFirst = Integer.MIN_VALUE;
            fadeLeft = 0;
            slidePending = false;
            pendingRelease = false;
            recheckIn = 0;

            for (Variant variant : drawn()) {
                variant.lastText = null;
            }
        }

        List<Variant> drawn() {
            List<Variant> all = new ArrayList<>(personal.size() + 1);
            all.add(shared);
            all.addAll(personal.values());
            return all;
        }
    }

    public void start() {
        stop();

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void clearAll() {
        for (Hologram state : states.values()) {
            removeEntity(state);
        }
        states.clear();
    }

    public void clear(Block block) {
        Hologram state = states.remove(block);
        if (state != null) removeEntity(state);
    }

    private static final long ANSWER_POLL_TICKS = 10L;

    private static final int ANSWER_POLL_LIMIT = 24;

    public void announce(Player player, Block block) {
        LavaPlayerManager.PlaybackInfo info = plugin.getAudioPlayerManager().getPlaybackInfo(block);
        if (info == null) {
            say(player, "§7", "gui.lyrics.status_idle");
            return;
        }
        if (info.live()) {
            say(player, "§7", "gui.lyrics.status_live");
            return;
        }
        if (plugin.getPortableJukeboxManager() != null
                && plugin.getPortableJukeboxManager().isCarried(block)) {
            say(player, "§7", "gui.lyrics.status_carried");
            return;
        }

        LyricsQuery query = LyricsQuery.of(info.author(), info.title(), info.duration());
        if (!query.isUsable()) {

            say(player, "§7", "gui.lyrics.status_missing");
            return;
        }

        if (settled(player, service.lookup(query))) return;

        say(player, "§7", "gui.lyrics.status_searching");
        awaitAnswer(player, block, query);
    }

    private boolean settled(Player player, LyricsService.Result result) {
        switch (result.state()) {
            case FOUND -> say(player, "§a", "gui.lyrics.status_found");
            case MISSING -> say(player, "§7", "gui.lyrics.status_missing");
            default -> {
                return false;
            }
        }
        return true;
    }

    private void awaitAnswer(Player player, Block block, LyricsQuery query) {
        UUID who = player.getUniqueId();
        if (!awaiting.add(who)) return;

        new org.bukkit.scheduler.BukkitRunnable() {
            private int asked = 0;

            @Override
            public void cancel() {
                awaiting.remove(who);
                super.cancel();
            }

            @Override
            public void run() {
                if (!player.isOnline() || !LyricsPrefs.isEnabled(block)) {
                    cancel();
                    return;
                }

                LavaPlayerManager.PlaybackInfo now =
                        plugin.getAudioPlayerManager().getPlaybackInfo(block);
                if (now == null || !query.cacheKey().equals(
                        LyricsQuery.of(now.author(), now.title(), now.duration()).cacheKey())) {
                    cancel();
                    return;
                }

                if (settled(player, service.lookup(query))) {
                    cancel();
                    return;
                }
                if (++asked >= ANSWER_POLL_LIMIT) {
                    say(player, "§7", "gui.lyrics.status_slow");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, ANSWER_POLL_TICKS, ANSWER_POLL_TICKS);
    }

    private void say(Player player, String colour, String key) {
        player.sendMessage(colour + plugin.getMessageManager().get(player, key));
    }

    public int sweepOrphans() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!isOurs(entity) || isMine(entity)) continue;
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private boolean isOurs(Entity entity) {
        return entity.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING);
    }

    private boolean isMine(Entity entity) {
        for (Hologram state : states.values()) {
            for (Variant variant : state.drawn()) {
                if (entity.equals(variant.entity)) return true;
            }
        }
        return false;
    }

    public Drawn drawn() {
        int total = 0;
        int personal = 0;

        for (Hologram state : states.values()) {
            if (alive(state.shared)) total++;
            for (Variant variant : state.personal.values()) {
                if (!alive(variant)) continue;
                total++;
                personal++;
            }
        }
        return new Drawn(total, personal);
    }

    public record Drawn(int total, int personal) {
    }

    private static boolean alive(Variant variant) {
        return !gone(variant.entity);
    }

    private static boolean gone(Entity entity) {
        return entity == null || entity.isDead();
    }

    private void removeEntity(Hologram state) {
        for (Variant variant : state.drawn()) {
            dropEntity(variant);
        }
        state.personal.clear();

        state.lastFrame = Long.MIN_VALUE;
        state.slidePending = false;
        state.pendingRelease = false;
    }

    private static void dropEntity(Variant variant) {
        if (variant.entity != null) {

            variant.entity.remove();
            variant.entity = null;
        }
        variant.lastText = null;
        variant.applied = null;
    }

    private void tick() {
        Config config = plugin.cdiscConfig();
        if (!config.isLyricsEnabled()) {
            if (!states.isEmpty()) clearAll();
            return;
        }

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        Set<Block> active = apm.activeBlocks();

        if (!states.isEmpty()) {
            states.keySet().stream()
                    .filter(block -> !active.contains(block))
                    .toList()
                    .forEach(this::clear);
        }

        if (!active.isEmpty()) {
            HologramStyle defaults = HologramStyle.fromConfig(config);
            for (Block block : active) {
                update(block, apm, config, defaults);
            }
        }

        if (--sweepIn <= 0) {
            sweepIn = SWEEP_TICKS;
            int stale = sweepStale(active);
            if (stale > 0) {
                plugin.getLogger().warning("Removed " + stale + " stale lyrics hologram(s) "
                        + "found floating over a playing jukebox.");
            }
        }
    }

    private int sweepStale(Set<Block> active) {
        int removed = 0;
        for (Block block : active) {
            World world = block.getWorld();
            if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) continue;

            for (Entity entity : world.getNearbyEntities(
                    block.getLocation().add(0.5, 0.5, 0.5),
                    SWEEP_RADIUS, SWEEP_HEIGHT, SWEEP_RADIUS)) {
                if (!isOurs(entity) || isMine(entity)) continue;
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private void update(Block block, LavaPlayerManager apm, Config config,
                        HologramStyle defaults) {
        Hologram state = states.computeIfAbsent(block, b -> new Hologram());

        LavaPlayerManager.PlaybackInfo info = apm.getPlaybackInfo(block);

        if (info == null || info.live()) {
            removeEntity(state);
            return;
        }

        if (info.duration() != state.trackDuration || !info.title().equals(state.trackTitle)) {
            state.trackTitle = info.title();
            state.trackDuration = info.duration();
            state.resetForNewTrack();
        }

        if (--state.recheckIn <= 0) {
            state.recheckIn = Math.max(1, config.getLyricsUpdateTicks());
            state.lyrics = resolve(block, info);
        }

        if (state.lyrics == null) {

            removeEntity(state);
            return;
        }

        render(block, state, info, config, defaults);
    }

    private SyncedLyrics resolve(Block block, LavaPlayerManager.PlaybackInfo info) {

        if (!LyricsPrefs.isEnabled(block)) return null;

        if (plugin.getPortableJukeboxManager() != null
                && plugin.getPortableJukeboxManager().isCarried(block)) {
            return null;
        }

        LyricsService.Result result = service.lookup(
                LyricsQuery.of(info.author(), info.title(), info.duration()));
        return result.isFound() ? result.lyrics() : null;
    }

    private void render(Block block, Hologram state, LavaPlayerManager.PlaybackInfo info,
                        Config config, HologramStyle defaults) {
        long position = info.position();
        SyncedLyrics lyrics = state.lyrics;

        HologramStyle jukebox = LyricsLook.resolve(block, defaults);

        int fadeTicks = jukebox.fadeTicks();

        int index = lyrics.indexAt(position);
        int first = index < 0 ? 0 : Math.max(0, index - jukebox.linesBefore());

        if (index != state.lastIndex) {

            boolean stepped = state.lastIndex != Integer.MIN_VALUE && index == state.lastIndex + 1;

            state.fadeLeft = stepped ? fadeTicks : 0;
            boolean scrolled = stepped && state.lastFirst != Integer.MIN_VALUE
                    && first == state.lastFirst + 1;

            state.lastIndex = index;
            state.lastFirst = first;

            if (scrolled && fadeTicks > 0 && jukebox.slide()) {

                state.slidePending = true;
            }
        }

        long fullCountdownMs = config.getLyricsCountdownSeconds() * 1000L;
        int countdownStep = LyricsRenderer.countdownStep(lyrics, position, fullCountdownMs);

        long frame = ((long) index << 32) | ((long) (countdownStep & 0xFFFF) << 16)
                | (state.fadeLeft & 0xFFFF);
        boolean sameFrame = frame == state.lastFrame;

        float fade = fadeProgress(state, fadeTicks);

        boolean drawn = draw(block, state, state.shared, jukebox, lyrics, position,
                fullCountdownMs, fade, sameFrame, config);

        syncPersonal(block, state);
        for (Map.Entry<UUID, Variant> entry : state.personal.entrySet()) {
            HologramStyle own = plugin.getHologramPresets().get(entry.getKey());
            if (own == null) continue;

            HologramStyle timed = own.withFadeTicks(fadeTicks).withSlide(jukebox.slide());
            drawn |= draw(block, state, entry.getValue(), timed, lyrics, position,
                    fullCountdownMs, fade, sameFrame, config);
        }

        if (!drawn) {

            if (state.fadeLeft > 0) state.fadeLeft--;
            return;
        }

        state.lastFrame = frame;

        advanceSlide(state, jukebox);

        if (state.fadeLeft > 0) state.fadeLeft--;
    }

    private boolean draw(Block block, Hologram state, Variant variant, HologramStyle style,
                         SyncedLyrics lyrics, long position, long fullCountdownMs, float fade,
                         boolean sameFrame, Config config) {
        Location location = hologramLocation(block, style.height());
        if (location == null) {

            dropEntity(variant);
            return false;
        }

        boolean fresh = false;
        if (gone(variant.entity)) {

            dropEntity(variant);

            variant.entity = spawn(location, style, config);
            if (variant.entity == null) return false;
            if (variant == state.shared) state.hiddenFromShared.clear();

            variant.applied = style;
            fresh = true;
        } else {
            if (!style.equals(variant.applied)) {
                applyStyle(variant.entity, style, config);
                variant.applied = style;
                variant.lastText = null;
            }
            if (moved(variant.entity.getLocation(), location)) {
                variant.entity.teleport(location);
            }
        }

        if (sameFrame && !fresh && variant.lastText != null) return true;

        LyricsRenderer.Options options = new LyricsRenderer.Options(
                style.linesBefore(), style.linesAfter(),
                style.lyricsStyle(),
                style.countdown() ? fullCountdownMs : 0L,
                config.getLyricsCountdownFilled(), config.getLyricsCountdownEmpty(),
                fade);

        List<String> lines = LyricsRenderer.window(lyrics, position, options);
        if (lines.isEmpty()) {
            dropEntity(variant);
            return false;
        }

        String text = String.join("\n", lines);
        if (!text.equals(variant.lastText)) {
            variant.entity.setText(text);
            variant.lastText = text;
        }
        return true;
    }

    private void syncPersonal(Block block, Hologram state) {
        HologramPresets presets = plugin.getHologramPresets();

        Iterator<Map.Entry<UUID, Variant>> existing = state.personal.entrySet().iterator();
        while (existing.hasNext()) {
            Map.Entry<UUID, Variant> entry = existing.next();
            Player owner = Bukkit.getPlayer(entry.getKey());

            if (owner == null || !presets.has(entry.getKey()) || !inRange(owner, block)) {
                dropEntity(entry.getValue());
                existing.remove();
                state.hiddenFromShared.remove(entry.getKey());
                if (owner != null) showShared(owner, state);
            }
        }

        if (presets.size() == 0) return;

        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            if (state.personal.containsKey(id)) continue;
            if (!presets.has(id) || !inRange(online, block)) continue;

            state.personal.put(id, new Variant());
        }

        for (UUID id : state.personal.keySet()) {
            if (state.hiddenFromShared.contains(id)) continue;

            Player owner = Bukkit.getPlayer(id);
            if (owner == null) continue;

            hideShared(owner, state);
            state.hiddenFromShared.add(id);
        }
    }

    private static boolean inRange(Player player, Block block) {
        return player.getWorld().equals(block.getWorld())
                && player.getLocation().distanceSquared(block.getLocation()) <= PERSONAL_RANGE_SQUARED;
    }

    private void hideShared(Player player, Hologram state) {
        if (!gone(state.shared.entity)) {
            player.hideEntity(plugin, state.shared.entity);
        }
    }

    private void showShared(Player player, Hologram state) {
        if (!gone(state.shared.entity)) {
            player.showEntity(plugin, state.shared.entity);
        }
    }

    private static float fadeProgress(Hologram state, int fadeTicks) {
        if (fadeTicks <= 0 || state.fadeLeft <= 0) return 1f;
        return 1f - (state.fadeLeft / (float) fadeTicks);
    }

    private void advanceSlide(Hologram state, HologramStyle jukebox) {
        if (state.slidePending) {
            for (Variant variant : state.drawn()) {
                float scale = scaleFor(sizeOf(variant, jukebox));
                applyTransform(variant.entity, -LINE_HEIGHT * scale, scale, 0);
            }
            state.slidePending = false;
            state.pendingRelease = true;
            return;
        }

        if (state.pendingRelease) {
            for (Variant variant : state.drawn()) {
                float scale = scaleFor(sizeOf(variant, jukebox));
                applyTransform(variant.entity, 0f, scale, jukebox.fadeTicks());
            }
            state.pendingRelease = false;
        }
    }

    private static int sizeOf(Variant variant, HologramStyle jukebox) {
        return variant.applied != null ? variant.applied.size() : jukebox.size();
    }

    private Location hologramLocation(Block block, double height) {
        World world = block.getWorld();
        if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return null;

        return block.getLocation().add(0.5, 0.5 + height, 0.5);
    }

    private static boolean moved(Location from, Location to) {
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return true;
        return from.distanceSquared(to) > 0.0025;
    }

    private TextDisplay spawn(Location location, HologramStyle style, Config config) {
        World world = location.getWorld();
        if (world == null) return null;

        try {
            return world.spawn(location, TextDisplay.class, display -> {
                display.setBillboard(Display.Billboard.CENTER);
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setViewRange(config.getLyricsViewRange());
                applyStyle(display, style, config);

                display.setText(" ");

                DisplayCompat.setTeleportDuration(display, 3);

                display.setPersistent(false);
                display.getPersistentDataContainer()
                        .set(displayKey, PersistentDataType.STRING, TAG_VALUE);
            });
        } catch (IllegalArgumentException e) {

            return null;
        }
    }

    private static void applyStyle(TextDisplay display, HologramStyle style, Config config) {
        display.setLineWidth(style.lineWidth());
        display.setShadowed(style.shadow());
        display.setSeeThrough(style.seeThrough());
        display.setBackgroundColor(background(style));
        applyBrightness(display, style.brightness());
        applyTransform(display, 0f, scaleFor(style.size()), 0);
    }

    public boolean showsJukeboxStyleTo(Player player, Block block) {
        if (plugin.getHologramPresets().has(player.getUniqueId())) return false;

        Hologram state = states.get(block);
        return state != null && alive(state.shared);
    }

    public boolean showsOwnStyleTo(Player player) {
        if (!plugin.getHologramPresets().has(player.getUniqueId())) return false;

        for (Map.Entry<Block, Hologram> entry : states.entrySet()) {
            Variant theirs = entry.getValue().personal.get(player.getUniqueId());
            if (theirs != null && alive(theirs) && inRange(player, entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    public void restyle(Block block) {
        Hologram state = states.get(block);
        if (state == null) return;

        for (Variant variant : state.drawn()) {
            variant.lastText = null;
            variant.applied = null;
        }
        state.lastFrame = Long.MIN_VALUE;
    }

    public void presetChanged(Player player) {
        UUID id = player.getUniqueId();
        boolean has = plugin.getHologramPresets().has(id);

        for (Hologram state : states.values()) {
            Variant variant = state.personal.remove(id);
            if (variant != null) dropEntity(variant);

            if (has) {
                hideShared(player, state);
                state.hiddenFromShared.add(id);
            } else {
                showShared(player, state);
                state.hiddenFromShared.remove(id);
            }
            state.lastFrame = Long.MIN_VALUE;
        }
    }

    public void presetRestyled(Player player) {
        for (Hologram state : states.values()) {
            if (state.personal.containsKey(player.getUniqueId())) {
                state.lastFrame = Long.MIN_VALUE;
            }
        }
    }

    private static void applyBrightness(TextDisplay display, int brightness) {
        display.setBrightness(brightness < 0
                ? null
                : new Display.Brightness(Math.min(15, brightness), Math.min(15, brightness)));
    }

    private static Color background(HologramStyle style) {
        int alpha = Math.max(0, Math.min(255, style.backgroundOpacity()));
        int rgb = style.backgroundColor();
        return Color.fromARGB(alpha, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static float scaleFor(int size) {
        return SIZE_SCALE[Math.max(1, Math.min(SIZE_SCALE.length, size)) - 1];
    }

    private static void applyTransform(TextDisplay display, float translationY,
                                       float scale, int interpolationTicks) {
        if (display == null || !display.isValid()) return;

        display.setInterpolationDelay(0);
        display.setInterpolationDuration(Math.max(0, interpolationTicks));

        Transformation current = display.getTransformation();
        display.setTransformation(new Transformation(
                new Vector3f(0f, translationY, 0f),
                current.getLeftRotation(),
                new Vector3f(scale, scale, scale),
                current.getRightRotation()));
    }
}
