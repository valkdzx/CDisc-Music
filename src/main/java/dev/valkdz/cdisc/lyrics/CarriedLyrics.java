package dev.valkdz.cdisc.lyrics;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.portable.PortableJukeboxManager;
import dev.valkdz.cdisc.util.Config;
import dev.valkdz.cdisc.util.DisplayCompat;
import dev.valkdz.cdisc.util.PlayerPrefs;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
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

public final class CarriedLyrics {

    private static final String TAG_VALUE = "cdisc-carried-lyrics";

    private static final double HEIGHT = 2.5;

    private static final double MOVED_ENOUGH = 1.0E-4;

    private static final double MIN_AUDIENCE_RANGE = 80;

    private static final double VIEW_RANGE_BLOCKS = 64;

    // Passes of this task, which itself runs every 5 ticks.
    private static final int AUDIENCE_PASSES = 2;

    private static final String OBJECTIVE = "cdisc_lyrics";

    private static final ChatColor[] ROW_KEYS = ChatColor.values();

    private static final int PREFIX_LIMIT = 64;

    private final Main plugin;
    private final LyricsService service;
    private final NamespacedKey displayKey;

    private final Map<UUID, Shown> shown = new HashMap<>();

    private BukkitTask task;
    private BukkitTask followTask;

    private double audienceRangeSquared = MIN_AUDIENCE_RANGE * MIN_AUDIENCE_RANGE;

    public CarriedLyrics(Main plugin, LyricsService service) {
        this.plugin = plugin;
        this.service = service;
        this.displayKey = new NamespacedKey(plugin, "cdisc_carried_lyrics");
    }

    private static final class Overhead {

        final HologramStyle style;

        final Set<UUID> viewers = new HashSet<>();

        final Set<UUID> shownTo = new HashSet<>();

        TextDisplay entity;

        Location sentTo;

        String lastText;

        boolean viewersChanged;

        Overhead(HologramStyle style) {
            this.style = style;
        }
    }

    private static final class Shown {
        Scoreboard board;
        Objective objective;

        final Map<HologramStyle, Overhead> overhead = new HashMap<>();

        final Map<UUID, Overhead> seats = new HashMap<>();

        int audienceIn;

        List<String> lastLines = List.of();
    }

    public void start() {
        stop();

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);

        // The words are written a few ticks apart, but they follow the carrier as often as
        // the sound does, or they trail behind the player they belong to.
        followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::follow, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
    }

    private void follow() {
        if (shown.isEmpty()) return;

        for (Map.Entry<UUID, Shown> entry : shown.entrySet()) {
            Shown state = entry.getValue();
            if (state.overhead.isEmpty()) continue;

            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;

            Location at = player.getLocation().add(0, HEIGHT, 0);
            for (Overhead group : state.overhead.values()) {
                if (gone(group.entity)) continue;
                moveTo(group, at);
            }
        }
    }

    private void moveTo(Overhead group, Location at) {
        if (!group.entity.getWorld().equals(at.getWorld())) {

            dropEntity(group);
            return;
        }

        Location sent = group.sentTo;
        if (sent != null && sent.getWorld() == at.getWorld()
                && sent.distanceSquared(at) <= MOVED_ENOUGH) {
            return;
        }

        group.sentTo = at;
        group.entity.teleport(at);
    }

    public void clearAll() {
        for (UUID id : new ArrayList<>(shown.keySet())) {
            clear(id);
        }
    }

    public int sweepOrphans() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!entity.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING)) {
                    continue;
                }
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private void tick() {
        Config config = plugin.cdiscConfig();
        PortableJukeboxManager portable = plugin.getPortableJukeboxManager();

        if (!config.isLyricsEnabled() || portable == null) {
            if (!shown.isEmpty()) clearAll();
            return;
        }

        Iterator<Map.Entry<UUID, Shown>> open = shown.entrySet().iterator();
        while (open.hasNext()) {
            UUID id = open.next().getKey();
            Player player = Bukkit.getPlayer(id);
            if (player == null || !wants(player, portable)) {
                take(shown.get(id), player);
                open.remove();
            }
        }

        double range = Math.max(MIN_AUDIENCE_RANGE,
                config.getLyricsViewRange() * VIEW_RANGE_BLOCKS);
        audienceRangeSquared = range * range;

        HologramStyle defaults = HologramStyle.fromConfig(config);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!wants(player, portable)) continue;
            draw(player, portable, config, defaults);
        }
    }

    private boolean wants(Player player, PortableJukeboxManager portable) {
        return PlayerPrefs.showsLyricsScoreboard(player) && portable.carryOf(player) != null;
    }

    private void draw(Player player, PortableJukeboxManager portable, Config config,
                      HologramStyle defaults) {
        PortableJukeboxManager.Carry carry = portable.carryOf(player);
        Block origin = carry.origin();

        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        LavaPlayerManager.PlaybackInfo info = apm.getPlaybackInfo(origin);
        if (info == null || info.live() || !LyricsPrefs.isEnabled(origin)) {
            hide(player);
            return;
        }

        LyricsService.Result result = service.lookup(
                LyricsQuery.of(info.author(), info.title(), info.duration()));
        if (!result.isFound()) {
            hide(player);
            return;
        }

        HologramStyle mine = plugin.getHologramPresets()
                .orDefault(player.getUniqueId(), defaults);

        List<String> lines = window(result.lyrics(), info.position(), mine, config);
        if (lines.isEmpty()) {
            hide(player);
            return;
        }

        Shown state = shown.computeIfAbsent(player.getUniqueId(), id -> new Shown());
        if (!lines.equals(state.lastLines)) {
            writeSidebar(player, state, lines, info);
            state.lastLines = lines;
        }
        writeOverhead(player, state, result.lyrics(), info.position(), config, defaults);
    }

    private List<String> window(SyncedLyrics lyrics, long position, HologramStyle style,
                                Config config) {
        return LyricsRenderer.window(lyrics, position,
                new LyricsRenderer.Options(style.linesBefore(), style.linesAfter(),
                        style.lyricsStyle(),
                        style.countdown() ? config.getLyricsCountdownSeconds() * 1000L : 0L,
                        config.getLyricsCountdownFilled(), config.getLyricsCountdownEmpty(),
                        1f));
    }

    private void writeSidebar(Player player, Shown state, List<String> lines,
                              LavaPlayerManager.PlaybackInfo info) {
        if (state.board == null) {
            state.board = Bukkit.getScoreboardManager().getNewScoreboard();
            state.objective = state.board.registerNewObjective(
                    OBJECTIVE, "dummy",
                    plugin.getMessageManager().get(player, "gui.lyrics.sidebar_title"));
            state.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        state.objective.setDisplayName("§b" + trim(info.title(), 30));

        int score = lines.size();
        for (int i = 0; i < lines.size() && i < ROW_KEYS.length; i++) {
            String key = ROW_KEYS[i].toString();
            Team row = state.board.getTeam("row" + i);
            if (row == null) row = state.board.registerNewTeam("row" + i);
            if (!row.hasEntry(key)) row.addEntry(key);

            row.setPrefix(trim(lines.get(i), PREFIX_LIMIT));
            state.objective.getScore(key).setScore(score--);
        }

        for (int i = lines.size(); i < ROW_KEYS.length; i++) {
            String key = ROW_KEYS[i].toString();
            if (state.board.getEntries().contains(key)) state.board.resetScores(key);
        }

        if (!player.getScoreboard().equals(state.board)) {
            player.setScoreboard(state.board);
        }
    }

    private void writeOverhead(Player carrier, Shown state, SyncedLyrics lyrics, long position,
                               Config config, HologramStyle defaults) {
        if (--state.audienceIn <= 0) {
            state.audienceIn = AUDIENCE_PASSES;
            seatAudience(carrier, state, defaults);
        }
        if (state.overhead.isEmpty()) return;

        Location at = carrier.getLocation().add(0, HEIGHT, 0);

        for (Overhead group : state.overhead.values()) {
            if (gone(group.entity)) {

                dropEntity(group);

                group.entity = spawn(at, group.style, config);
                if (group.entity == null) continue;

                group.sentTo = at;
                group.viewersChanged = true;
            } else {
                moveTo(group, at);
                if (group.entity == null) continue;
            }

            if (group.viewersChanged) showToViewers(group);

            String text = String.join("\n", window(lyrics, position, group.style, config));
            if (!text.equals(group.lastText)) {
                group.entity.setText(text);
                group.lastText = text;
            }
        }
    }

    private void seatAudience(Player carrier, Shown state, HologramStyle defaults) {
        HologramPresets presets = plugin.getHologramPresets();

        Iterator<Map.Entry<UUID, Overhead>> seated = state.seats.entrySet().iterator();
        while (seated.hasNext()) {
            Map.Entry<UUID, Overhead> entry = seated.next();
            Player viewer = Bukkit.getPlayer(entry.getKey());

            if (viewer != null && inRange(viewer, carrier) && entry.getValue().style
                    .equals(presets.orDefault(entry.getKey(), defaults))) {
                continue;
            }

            leave(entry.getValue(), entry.getKey(), viewer);
            seated.remove();
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            if (state.seats.containsKey(id) || !inRange(online, carrier)) continue;

            Overhead group = state.overhead
                    .computeIfAbsent(presets.orDefault(id, defaults), Overhead::new);

            group.viewers.add(id);
            group.viewersChanged = true;
            state.seats.put(id, group);
        }

        state.overhead.values().removeIf(group -> {
            if (!group.viewers.isEmpty()) return false;

            dropEntity(group);
            return true;
        });
    }

    private boolean inRange(Player viewer, Player carrier) {
        return !viewer.getUniqueId().equals(carrier.getUniqueId())
                && viewer.getWorld().equals(carrier.getWorld())
                && viewer.getLocation().distanceSquared(carrier.getLocation())
                <= audienceRangeSquared;
    }

    private void leave(Overhead group, UUID id, Player viewer) {
        group.viewers.remove(id);

        if (group.shownTo.remove(id) && viewer != null && !gone(group.entity)) {
            viewer.hideEntity(plugin, group.entity);
        }
    }

    private void showToViewers(Overhead group) {
        group.viewersChanged = false;

        for (UUID id : group.viewers) {
            if (group.shownTo.contains(id)) continue;

            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null) continue;

            viewer.showEntity(plugin, group.entity);
            group.shownTo.add(id);
        }
    }

    private void dropEntity(Overhead group) {
        if (group.entity != null) {

            for (UUID id : group.shownTo) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null) viewer.hideEntity(plugin, group.entity);
            }

            group.entity.remove();
            group.entity = null;
        }
        group.shownTo.clear();
        group.sentTo = null;
        group.lastText = null;
    }

    private TextDisplay spawn(Location at, HologramStyle style, Config config) {
        World world = at.getWorld();
        if (world == null) return null;

        try {
            return world.spawn(at, TextDisplay.class, display -> {
                display.setBillboard(Display.Billboard.CENTER);
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setLineWidth(style.lineWidth());
                display.setShadowed(style.shadow());
                display.setSeeThrough(style.seeThrough());
                display.setViewRange(config.getLyricsViewRange());
                display.setBackgroundColor(Color.fromARGB(
                        Math.max(0, Math.min(255, style.backgroundOpacity())),
                        (style.backgroundColor() >> 16) & 0xFF,
                        (style.backgroundColor() >> 8) & 0xFF,
                        style.backgroundColor() & 0xFF));

                float scale = 0.8f;
                display.setTransformation(new Transformation(
                        new Vector3f(0f, 0f, 0f),
                        display.getTransformation().getLeftRotation(),
                        new Vector3f(scale, scale, scale),
                        display.getTransformation().getRightRotation()));

                display.setText(" ");

                DisplayCompat.setTeleportDuration(display, 3);
                display.setPersistent(false);

                // The carrier reads the sidebar, and everyone else reads their own preset,
                // so this copy stays invisible until its group is shown it by hand.
                display.setVisibleByDefault(false);
                display.getPersistentDataContainer()
                        .set(displayKey, PersistentDataType.STRING, TAG_VALUE);
            });
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void hide(Player player) {
        Shown state = shown.remove(player.getUniqueId());
        if (state != null) take(state, player);
    }

    public void clear(UUID id) {
        Shown state = shown.remove(id);
        if (state != null) take(state, Bukkit.getPlayer(id));
    }

    public void presetChanged(Player player) {
        UUID id = player.getUniqueId();

        for (Shown state : shown.values()) {
            Overhead seat = state.seats.remove(id);
            if (seat != null) leave(seat, id, player);

            state.audienceIn = 0;
        }
    }

    private void take(Shown state, Player player) {
        if (state == null) return;

        for (Overhead group : state.overhead.values()) {
            dropEntity(group);
        }
        state.overhead.clear();
        state.seats.clear();
        state.audienceIn = 0;

        if (player != null && player.isOnline() && state.board != null
                && player.getScoreboard().equals(state.board)) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        state.board = null;
        state.objective = null;
        state.lastLines = List.of();
    }

    private static boolean gone(Entity entity) {
        return entity == null || entity.isDead();
    }

    private static String trim(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
