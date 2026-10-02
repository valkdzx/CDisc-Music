package dev.valkdz.cdisc.feature.lyrics;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.feature.lyrics.chat.ChatFeed;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import dev.valkdz.cdisc.util.Config;
import dev.valkdz.cdisc.util.DisplayCompat;
import dev.valkdz.cdisc.util.Tasks;
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
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class LyricsDisplay {

    private static final String TAG_VALUE = "cdisc-lyrics-display";

    private static final double MIN_AUDIENCE_RANGE = 80;

    private static final double VIEW_RANGE_BLOCKS = 64;

    private static final int AUDIENCE_TICKS = 10;

    private static final float[] SIZE_SCALE = {
            0.5f, 0.6f, 0.7f, 0.85f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f
    };

    private static final float LINE_HEIGHT = 0.25f;

    private static final int FOOTER_DROP_LINES = 2;

    private static final int FOOTER_SINK_LINES = 1;

    private static final int FOOTER_LINE_WIDTH = 1000;

    private static final int SWEEP_TICKS = 20 * 60;

    private static final double SWEEP_RADIUS = 4;
    private static final double SWEEP_HEIGHT = 8;

    private final Main plugin;
    private final LyricsService service;
    private final NamespacedKey displayKey;

    private final Map<Block, Hologram> states = new ConcurrentHashMap<>();

    private final Set<UUID> awaiting = ConcurrentHashMap.newKeySet();

    private Tasks.Handle task;

    private int sweepIn = SWEEP_TICKS;

    private double audienceRangeSquared = MIN_AUDIENCE_RANGE * MIN_AUDIENCE_RANGE;

    public LyricsDisplay(Main plugin, LyricsService service) {
        this.plugin = plugin;
        this.service = service;
        this.displayKey = new NamespacedKey(plugin, "cdisc_lyrics_display");
    }

    private record Seat(HologramStyle style, LyricsMode mode) {
    }

    private static final class Variant {

        final HologramStyle style;

        final LyricsMode mode;

        final Set<UUID> viewers = new HashSet<>();

        final Set<UUID> shownTo = new HashSet<>();

        final Set<UUID> footerShownTo = new HashSet<>();

        TextDisplay entity;

        TextDisplay footer;

        String lastText;

        String lastFooter;

        String lastHeader;

        boolean viewersChanged;

        long lastFrame = Long.MIN_VALUE;

        int lastIndex = Integer.MIN_VALUE;

        int lastFirst = Integer.MIN_VALUE;

        int fadeLeft;

        boolean slidePending;

        boolean pendingRelease;

        Variant(Seat seat) {
            this.style = seat.style();
            this.mode = seat.mode();
        }

        void rewind() {
            lastText = null;
            lastFooter = null;
            lastHeader = null;
            lastFrame = Long.MIN_VALUE;
            lastIndex = Integer.MIN_VALUE;
            lastFirst = Integer.MIN_VALUE;
            fadeLeft = 0;
            slidePending = false;
            pendingRelease = false;
        }
    }

    private static final class Hologram {

        final Map<Seat, Variant> variants = new HashMap<>();

        final Map<UUID, Variant> seats = new HashMap<>();

        String trackTitle;
        long trackDuration = Long.MIN_VALUE;

        int recheckIn;

        int audienceIn;

        SyncedLyrics lyrics;

        boolean wantedLyrics;

        void resetForNewTrack() {
            lyrics = null;
            recheckIn = 0;

            for (Variant variant : variants.values()) {
                variant.rewind();
            }
        }
    }

    public void start() {
        stop();

        task = Tasks.globalTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void clearAll() {
        for (Hologram state : states.values()) {
            clearVariants(state);
        }
        states.clear();
    }

    public void clear(Block block) {
        Hologram state = states.remove(block);
        if (state != null) clearVariants(state);
    }

    private static final long ANSWER_POLL_TICKS = 10L;

    private static final int ANSWER_POLL_LIMIT = 24;

    public void announce(Player player, Block block) {
        PlaybackManager.PlaybackInfo info = plugin.getAudioPlayerManager().getPlaybackInfo(block);
        if (info == null) {
            say(player, "§7", "gui.lyrics.status_idle");
            return;
        }
        if (info.live()) {
            boolean chat = plugin.getLiveChat() != null && plugin.getLiveChat().supports(info.uri());
            say(player, chat ? "§a" : "§7", chat ? "gui.lyrics.status_chat" : "gui.lyrics.status_live");
            return;
        }
        if (plugin.getPortableJukeboxManager() != null
                && plugin.getPortableJukeboxManager().isCarried(block)) {
            say(player, "§7", "gui.lyrics.status_carried");
            return;
        }

        if (info.ownLyrics() != null) {
            say(player, "§a", "gui.lyrics.status_found");
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

        Tasks.Handle[] poll = new Tasks.Handle[1];
        int[] asked = {0};

        Runnable give = () -> {
            awaiting.remove(who);
            if (poll[0] != null) poll[0].cancel();
        };

        poll[0] = Tasks.entityTimer(plugin, player, () -> {
            if (!player.isOnline() || !LyricsPrefs.mode(player).showsLyrics()) {
                give.run();
                return;
            }

            PlaybackManager.PlaybackInfo now =
                    plugin.getAudioPlayerManager().getPlaybackInfo(block);
            if (now == null || !query.cacheKey().equals(
                    LyricsQuery.of(now.author(), now.title(), now.duration()).cacheKey())) {
                give.run();
                return;
            }

            if (settled(player, service.lookup(query))) {
                give.run();
                return;
            }
            if (++asked[0] >= ANSWER_POLL_LIMIT) {
                say(player, "§7", "gui.lyrics.status_slow");
                give.run();
            }
        }, ANSWER_POLL_TICKS, ANSWER_POLL_TICKS);
    }

    private void say(Player player, String colour, String key) {
        player.sendMessage(colour + plugin.getMessageManager().get(player, key));
    }

    public int sweepOrphans() {
        // Folia has no world-wide entity view from the global thread, and none of these persist.
        if (Tasks.isFolia()) return 0;

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
            for (Variant variant : state.variants.values()) {
                if (entity.equals(variant.entity) || entity.equals(variant.footer)) return true;
            }
        }
        return false;
    }

    public Drawn drawn() {
        int total = 0;
        int watching = 0;

        for (Hologram state : states.values()) {
            for (Variant variant : state.variants.values()) {
                if (!alive(variant)) continue;

                total++;
                watching += variant.viewers.size();
            }
        }
        return new Drawn(total, watching);
    }

    public record Drawn(int total, int watching) {
    }

    private static boolean alive(Variant variant) {
        return !gone(variant.entity);
    }

    private static boolean gone(Entity entity) {
        return entity == null || entity.isDead();
    }

    private void clearVariants(Hologram state) {
        for (Variant variant : state.variants.values()) {
            dropEntity(variant);
        }
        state.variants.clear();
        state.seats.clear();
        state.audienceIn = 0;
    }

    private void dropEntity(Variant variant) {
        if (variant.entity != null) {

            for (UUID id : variant.shownTo) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null) viewer.hideEntity(plugin, variant.entity);
            }

            TextDisplay going = variant.entity;
            Tasks.onEntity(plugin, going, going::remove);
            variant.entity = null;
        }
        variant.shownTo.clear();
        variant.lastText = null;
        variant.lastHeader = null;
        variant.lastFrame = Long.MIN_VALUE;
        dropFooter(variant);
    }

    private void dropFooter(Variant variant) {
        if (variant.footer != null) {
            for (UUID id : variant.footerShownTo) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null) viewer.hideEntity(plugin, variant.footer);
            }

            TextDisplay going = variant.footer;
            Tasks.onEntity(plugin, going, going::remove);
            variant.footer = null;
        }
        variant.footerShownTo.clear();
        variant.lastFooter = null;
    }

    private void tick() {
        Config config = plugin.cdiscConfig();
        if (!config.isLyricsEnabled()) {
            if (!states.isEmpty()) clearAll();
            return;
        }

        PlaybackManager apm = plugin.getAudioPlayerManager();
        Set<Block> active = apm.activeBlocks();

        if (!states.isEmpty()) {
            states.keySet().stream()
                    .filter(block -> !active.contains(block))
                    .toList()
                    .forEach(this::clear);
        }

        if (!active.isEmpty()) {
            double range = Math.max(MIN_AUDIENCE_RANGE,
                    config.getLyricsViewRange() * VIEW_RANGE_BLOCKS);
            audienceRangeSquared = range * range;

            HologramStyle defaults = HologramStyle.fromConfig(config);
            for (Block block : active) {
                Tasks.inRegion(plugin, block, () -> update(block, apm, config, defaults));
            }
        }

        if (--sweepIn <= 0) {
            sweepIn = SWEEP_TICKS;
            sweepStale(active);
        }
    }

    private void sweepStale(Set<Block> active) {
        for (Block block : active) {
            Tasks.inRegion(plugin, block, () -> sweepAround(block));
        }
    }

    private void sweepAround(Block block) {
        World world = block.getWorld();
        if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return;

        int removed = 0;
        for (Entity entity : world.getNearbyEntities(
                block.getLocation().add(0.5, 0.5, 0.5),
                SWEEP_RADIUS, SWEEP_HEIGHT, SWEEP_RADIUS)) {
            if (!isOurs(entity) || isMine(entity)) continue;
            entity.remove();
            removed++;
        }

        if (removed > 0) {
            plugin.getLogger().warning("Removed " + removed + " stale lyrics hologram(s) "
                    + "found floating over a playing jukebox.");
        }
    }

    private void update(Block block, PlaybackManager apm, Config config,
                        HologramStyle defaults) {
        Hologram state = states.computeIfAbsent(block, b -> new Hologram());

        PlaybackManager.PlaybackInfo info = apm.getPlaybackInfo(block);

        if (info == null || (plugin.getPortableJukeboxManager() != null
                && plugin.getPortableJukeboxManager().isCarried(block))) {
            clearVariants(state);
            return;
        }

        ChatFeed.Snapshot chat = null;
        if (info.live()) {
            chat = plugin.getLiveChat() == null ? null : plugin.getLiveChat().read(info.uri());
            if (chat == null) {
                clearVariants(state);
                return;
            }
        }

        if (info.duration() != state.trackDuration || !info.title().equals(state.trackTitle)) {
            state.trackTitle = info.title();
            state.trackDuration = info.duration();
            state.resetForNewTrack();
        }

        if (--state.audienceIn <= 0) {
            state.audienceIn = AUDIENCE_TICKS;
            seatAudience(block, state, defaults);
        }
        if (state.variants.isEmpty()) return;

        boolean wantsLyrics = false;
        for (Variant variant : state.variants.values()) {
            wantsLyrics |= variant.mode.showsLyrics() && chat == null;
        }
        if (wantsLyrics && !state.wantedLyrics) state.recheckIn = 0;
        state.wantedLyrics = wantsLyrics;

        if (--state.recheckIn <= 0) {
            state.recheckIn = Math.max(1, config.getLyricsUpdateTicks());
            state.lyrics = wantsLyrics ? lookup(info) : null;
        }

        if (chat != null) renderChat(block, state, info, chat, config);
        else render(block, state, info, config);
    }

    private SyncedLyrics lookup(PlaybackManager.PlaybackInfo info) {
        LyricsService.Result result = service.lookup(info);
        return result.isFound() ? result.lyrics() : null;
    }

    private void render(Block block, Hologram state, PlaybackManager.PlaybackInfo info,
                        Config config) {
        long position = info.position();
        SyncedLyrics found = state.lyrics;
        int foundIndex = found == null ? -1 : found.indexAt(position);

        long fullCountdownMs = config.getLyricsCountdownSeconds() * 1000L;
        int foundCountdown = LyricsRenderer.countdownStep(found, position, fullCountdownMs);

        for (Variant variant : state.variants.values()) {
            SyncedLyrics lyrics = variant.mode.showsLyrics() ? found : null;
            String header = variant.mode.header(
                    info.title(), info.author(), position, info.duration());
            if (lyrics == null && header == null) {
                dropEntity(variant);
                continue;
            }

            int index = lyrics == null ? -1 : foundIndex;
            int countdownStep = lyrics == null ? 0 : foundCountdown;
            step(variant, index, index < 0 ? 0 : Math.max(0, index - variant.style.linesBefore()));

            long frame = ((long) index << 32) | ((long) (countdownStep & 0xFFFF) << 16)
                    | (variant.fadeLeft & 0xFFFF);
            boolean sameFrame = frame == variant.lastFrame
                    && Objects.equals(header, variant.lastHeader);

            if (draw(block, variant, lyrics == null ? null
                            : options -> LyricsRenderer.window(lyrics, position, options),
                    header, fullCountdownMs, sameFrame, config)) {

                variant.lastFrame = frame;
                variant.lastHeader = header;
                advanceSlide(variant);
            }

            if (variant.fadeLeft > 0) variant.fadeLeft--;
        }
    }

    private void renderChat(Block block, Hologram state, PlaybackManager.PlaybackInfo info,
                            ChatFeed.Snapshot chat, Config config) {
        int index = chat.index();

        for (Variant variant : state.variants.values()) {
            String header = variant.mode.liveHeader(info.title(), info.author(), info.position());
            if (!variant.mode.showsLyrics() || (index < 0 && header == null)) {
                dropEntity(variant);
                continue;
            }

            // Every new message scrolls, even while the window is still filling up.
            step(variant, index, index);

            long frame = ((long) index << 32) | (variant.fadeLeft & 0xFFFF);
            boolean sameFrame = frame == variant.lastFrame
                    && Objects.equals(header, variant.lastHeader);

            if (draw(block, variant, index < 0 ? null
                            : options -> LyricsRenderer.chat(chat.messages(), options,
                                    variant.style.textOpacity()),
                    header, 0L, sameFrame, config)) {

                variant.lastFrame = frame;
                variant.lastHeader = header;
                advanceSlide(variant);
            }

            if (variant.fadeLeft > 0) variant.fadeLeft--;
        }
    }

    private void seatAudience(Block block, Hologram state, HologramStyle defaults) {
        HologramPresets presets = plugin.getHologramPresets();

        Iterator<Map.Entry<UUID, Variant>> seated = state.seats.entrySet().iterator();
        while (seated.hasNext()) {
            Map.Entry<UUID, Variant> entry = seated.next();
            Player viewer = Bukkit.getPlayer(entry.getKey());
            Variant variant = entry.getValue();

            if (viewer != null && inRange(viewer, block)
                    && variant.mode == LyricsPrefs.mode(viewer)
                    && variant.style.equals(presets.orDefault(entry.getKey(), defaults))) {
                continue;
            }

            leave(variant, entry.getKey(), viewer);
            seated.remove();
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            if (state.seats.containsKey(id) || !inRange(online, block)) continue;

            LyricsMode mode = LyricsPrefs.mode(online);
            if (mode == LyricsMode.OFF) continue;

            Variant variant = state.variants.computeIfAbsent(
                    new Seat(presets.orDefault(id, defaults), mode), Variant::new);

            variant.viewers.add(id);
            variant.viewersChanged = true;
            state.seats.put(id, variant);
        }

        state.variants.values().removeIf(variant -> {
            if (!variant.viewers.isEmpty()) return false;

            dropEntity(variant);
            return true;
        });
    }

    private void leave(Variant variant, UUID id, Player viewer) {
        variant.viewers.remove(id);

        if (variant.shownTo.remove(id) && viewer != null && !gone(variant.entity)) {
            viewer.hideEntity(plugin, variant.entity);
        }
        if (variant.footerShownTo.remove(id) && viewer != null && !gone(variant.footer)) {
            viewer.hideEntity(plugin, variant.footer);
        }
    }

    private void showToViewers(Variant variant) {
        variant.viewersChanged = false;

        for (UUID id : variant.viewers) {
            if (variant.shownTo.contains(id)) continue;

            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null) continue;

            viewer.showEntity(plugin, variant.entity);
            variant.shownTo.add(id);
        }

        if (gone(variant.footer)) return;
        for (UUID id : variant.viewers) {
            if (variant.footerShownTo.contains(id)) continue;

            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null) continue;

            viewer.showEntity(plugin, variant.footer);
            variant.footerShownTo.add(id);
        }
    }

    private static void step(Variant variant, int index, int first) {
        if (index == variant.lastIndex) return;

        HologramStyle style = variant.style;

        boolean stepped = variant.lastIndex != Integer.MIN_VALUE && index == variant.lastIndex + 1;
        boolean scrolled = stepped && variant.lastFirst != Integer.MIN_VALUE
                && first == variant.lastFirst + 1;

        variant.fadeLeft = stepped ? style.fadeTicks() : 0;
        variant.lastIndex = index;
        variant.lastFirst = first;

        if (scrolled && style.fadeTicks() > 0 && style.slide()) {
            variant.slidePending = true;
        }
    }

    private boolean draw(Block block, Variant variant,
                         Function<LyricsRenderer.Options, List<String>> body, String header,
                         long fullCountdownMs, boolean sameFrame, Config config) {
        HologramStyle style = variant.style;

        Location base = hologramLocation(block, style.height());
        if (base == null) {

            dropEntity(variant);
            return false;
        }

        // Under the words the track line is its own entity, so sliding the words leaves it still.
        String footer = body != null ? header : null;
        float line = LINE_HEIGHT * scaleFor(style.size());
        Location footerAt = base.clone().subtract(0, FOOTER_SINK_LINES * line, 0);
        Location location = footer == null ? base
                : footerAt.clone().add(0, FOOTER_DROP_LINES * line, 0);

        boolean fresh = false;
        if (gone(variant.entity)) {

            dropEntity(variant);

            variant.entity = spawn(location, style, config);
            if (variant.entity == null) return false;

            variant.viewersChanged = true;
            fresh = true;
        } else if (moved(variant.entity.getLocation(), location)) {
            Tasks.teleport(variant.entity, location);
        }

        boolean freshFooter = false;
        if (footer != null) {
            freshFooter = placeFooter(variant, footerAt, style, config);
            if (variant.footer == null) footer = null;
        } else {
            dropFooter(variant);
        }

        if (variant.viewersChanged) showToViewers(variant);

        if (footer != null) {
            String footerText = style.trackColor() + footer;
            if (!footerText.equals(variant.lastFooter)) {
                variant.footer.setText(footerText);
                variant.lastFooter = footerText;
            }
        }

        if (sameFrame && !fresh && !freshFooter && variant.lastText != null) return true;

        LyricsRenderer.Options options = new LyricsRenderer.Options(
                style.linesBefore(), style.linesAfter(),
                style.lyricsStyle(),
                style.countdown() ? fullCountdownMs : 0L,
                config.getLyricsCountdownFilled(), config.getLyricsCountdownEmpty(),
                fadeProgress(variant));

        List<String> lines = new ArrayList<>();
        if (body != null) lines.addAll(body.apply(options));
        if (body == null && header != null) lines.add(style.trackColor() + header);
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

    private boolean placeFooter(Variant variant, Location at, HologramStyle style,
                                Config config) {
        if (!gone(variant.footer)) {
            if (moved(variant.footer.getLocation(), at)) Tasks.teleport(variant.footer, at);
            return false;
        }

        dropFooter(variant);
        variant.footer = spawn(at, style, config);
        if (variant.footer == null) return false;

        variant.footer.setLineWidth(FOOTER_LINE_WIDTH);
        variant.viewersChanged = true;
        return true;
    }

    private boolean inRange(Player player, Block block) {
        return player.getWorld().equals(block.getWorld())
                && player.getLocation().distanceSquared(block.getLocation())
                <= audienceRangeSquared;
    }

    private static float fadeProgress(Variant variant) {
        int fadeTicks = variant.style.fadeTicks();
        if (fadeTicks <= 0 || variant.fadeLeft <= 0) return 1f;

        return 1f - (variant.fadeLeft / (float) fadeTicks);
    }

    private static void advanceSlide(Variant variant) {
        float scale = scaleFor(variant.style.size());

        if (variant.slidePending) {
            applyTransform(variant.entity, -LINE_HEIGHT * scale, scale, 0);
            variant.slidePending = false;
            variant.pendingRelease = true;
            return;
        }

        if (variant.pendingRelease) {
            applyTransform(variant.entity, 0f, scale, variant.style.fadeTicks());
            variant.pendingRelease = false;
        }
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
                applyStyle(display, style);

                display.setText(" ");

                DisplayCompat.setTeleportDuration(display, 3);

                display.setPersistent(false);

                // Each reader gets the copy their own preset asked for, so nobody is shown
                // this one until seatAudience puts them on it.
                display.setVisibleByDefault(false);
                display.getPersistentDataContainer()
                        .set(displayKey, PersistentDataType.STRING, TAG_VALUE);
            });
        } catch (IllegalArgumentException e) {

            return null;
        }
    }

    private static void applyStyle(TextDisplay display, HologramStyle style) {
        display.setLineWidth(style.lineWidth());
        display.setShadowed(style.shadow());
        display.setSeeThrough(style.seeThrough());
        display.setBackgroundColor(background(style));
        applyBrightness(display, style.brightness());
        applyTransform(display, 0f, scaleFor(style.size()), 0);
    }

    public boolean showsOwnStyleTo(Player player) {

        // Asked right after a preset edit, when the reader is between seats, so it goes by
        // range rather than by the seat they are about to be given.
        for (Map.Entry<Block, Hologram> entry : states.entrySet()) {
            if (!inRange(player, entry.getKey())) continue;

            for (Variant variant : entry.getValue().variants.values()) {
                if (alive(variant)) return true;
            }
        }
        return false;
    }

    public void presetChanged(Player player) {
        UUID id = player.getUniqueId();

        for (Hologram state : states.values()) {
            Variant seat = state.seats.remove(id);
            if (seat != null) leave(seat, id, player);

            state.audienceIn = 0;
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
        // Not isValid(): it is false inside the spawn consumer, where the preset's scale is set.
        if (display == null || display.isDead()) return;

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
