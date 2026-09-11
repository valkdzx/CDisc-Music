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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CarriedLyrics {

    private static final String TAG_VALUE = "cdisc-carried-lyrics";

    private static final double HEIGHT = 2.5;

    private static final double MOVED_ENOUGH = 1.0E-4;

    private static final String OBJECTIVE = "cdisc_lyrics";

    private static final ChatColor[] ROW_KEYS = ChatColor.values();

    private static final int PREFIX_LIMIT = 64;

    private final Main plugin;
    private final LyricsService service;
    private final NamespacedKey displayKey;

    private final Map<UUID, Shown> shown = new HashMap<>();

    private BukkitTask task;
    private BukkitTask followTask;

    public CarriedLyrics(Main plugin, LyricsService service) {
        this.plugin = plugin;
        this.service = service;
        this.displayKey = new NamespacedKey(plugin, "cdisc_carried_lyrics");
    }

    private static final class Shown {
        Scoreboard board;
        Objective objective;
        TextDisplay entity;

        Location sentTo;

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
            if (gone(state.entity)) continue;

            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;

            moveTo(state, player.getLocation().add(0, HEIGHT, 0));
        }
    }

    private void moveTo(Shown state, Location at) {
        if (!state.entity.getWorld().equals(at.getWorld())) {

            state.entity.remove();
            state.entity = null;
            state.sentTo = null;
            return;
        }

        Location sent = state.sentTo;
        if (sent != null && sent.getWorld() == at.getWorld()
                && sent.distanceSquared(at) <= MOVED_ENOUGH) {
            return;
        }

        state.sentTo = at;
        state.entity.teleport(at);
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

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!wants(player, portable)) continue;
            draw(player, portable, config);
        }
    }

    private boolean wants(Player player, PortableJukeboxManager portable) {
        return PlayerPrefs.showsLyricsScoreboard(player) && portable.carryOf(player) != null;
    }

    private void draw(Player player, PortableJukeboxManager portable, Config config) {
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

        HologramStyle style = LyricsLook.resolve(origin, HologramStyle.fromConfig(config));
        List<String> lines = LyricsRenderer.window(result.lyrics(), info.position(),
                new LyricsRenderer.Options(style.linesBefore(), style.linesAfter(),
                        style.lyricsStyle(),
                        style.countdown() ? config.getLyricsCountdownSeconds() * 1000L : 0L,
                        config.getLyricsCountdownFilled(), config.getLyricsCountdownEmpty(),
                        1f));

        if (lines.isEmpty()) {
            hide(player);
            return;
        }

        Shown state = shown.computeIfAbsent(player.getUniqueId(), id -> new Shown());
        if (!lines.equals(state.lastLines)) {
            writeSidebar(player, state, lines, info);
            state.lastLines = lines;
        }
        writeOverhead(player, state, style, lines, config);
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

    private void writeOverhead(Player player, Shown state, HologramStyle style,
                               List<String> lines, Config config) {
        Location at = player.getLocation().add(0, HEIGHT, 0);

        if (gone(state.entity)) {

            if (state.entity != null) state.entity.remove();

            state.entity = spawn(at, style, config);
            if (state.entity == null) return;
            state.sentTo = at;
            player.hideEntity(plugin, state.entity);
        } else {
            moveTo(state, at);
            if (state.entity == null) return;
        }

        String text = String.join("\n", lines);
        if (!text.equals(state.entity.getText())) state.entity.setText(text);
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

    private void take(Shown state, Player player) {
        if (state == null) return;

        if (state.entity != null) {

            state.entity.remove();
            state.entity = null;
        }

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
