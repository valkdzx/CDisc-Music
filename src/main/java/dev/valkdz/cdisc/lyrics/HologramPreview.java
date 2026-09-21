package dev.valkdz.cdisc.lyrics;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.DisplayCompat;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HologramPreview {

    private static final String TAG_VALUE = "cdisc-hologram-preview";

    private static final double DISTANCE = 2.2;

    private static final long FOLLOW_TICKS = 5L;

    private static final long SAMPLE_POSITION = 60_000L;

    private static final int SAMPLE_LINES = 13;
    private static final int SAMPLE_PHRASES = 4;

    private static final float[] SIZE_SCALE = {
            0.5f, 0.6f, 0.7f, 0.85f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f
    };

    private final Main plugin;
    private final NamespacedKey previewKey;

    private final Map<UUID, TextDisplay> shown = new java.util.concurrent.ConcurrentHashMap<>();

    private Tasks.Handle follow;

    public HologramPreview(Main plugin) {
        this.plugin = plugin;
        this.previewKey = new NamespacedKey(plugin, "cdisc_hologram_preview");
    }

    public int sweepOrphans() {
        // Folia has no world-wide entity view from the global thread, and none of these persist.
        if (Tasks.isFolia()) return 0;

        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!entity.getPersistentDataContainer().has(previewKey, PersistentDataType.STRING)) {
                    continue;
                }
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    public void show(Player player, HologramStyle style) {
        Location location = inFrontOf(player);
        if (location == null) return;

        TextDisplay display = shown.get(player.getUniqueId());
        if (gone(display)) {

            if (display != null) display.remove();

            display = spawn(player, location, style);
            if (display == null) return;
            shown.put(player.getUniqueId(), display);
        } else {
            apply(display, style);
            Tasks.teleport(display, location);
        }

        display.setText(sampleText(player, style));
        startFollowing();
    }

    public void hide(Player player) {
        TextDisplay display = shown.remove(player.getUniqueId());
        if (display != null) display.remove();
        stopFollowingIfIdle();
    }

    public void hideAll() {
        for (TextDisplay display : shown.values()) {
            Tasks.onEntity(plugin, display, display::remove);
        }
        shown.clear();
        stopFollowingIfIdle();
    }

    private String sampleText(Player player, HologramStyle style) {
        SyncedLyrics sample = SyncedLyrics.parse(sampleLrc(player));
        if (sample == null) return " ";

        LyricsRenderer.Options options = new LyricsRenderer.Options(
                style.linesBefore(), style.linesAfter(),
                style.lyricsStyle(),

                0L, plugin.cdiscConfig().getLyricsCountdownFilled(),
                plugin.cdiscConfig().getLyricsCountdownEmpty(),
                1f);

        List<String> lines = LyricsRenderer.window(sample, SAMPLE_POSITION, options);
        return lines.isEmpty() ? " " : String.join("\n", lines);
    }

    private String sampleLrc(Player player) {
        StringBuilder lrc = new StringBuilder();
        for (int i = 0; i < SAMPLE_LINES; i++) {
            String phrase = plugin.getMessageManager()
                    .get(player, "gui.lyrics_look.preview_" + (i % SAMPLE_PHRASES + 1));

            lrc.append(String.format("[%02d:%02d.00]", i / 6, (i * 10) % 60))
                    .append(phrase)
                    .append('\n');
        }
        return lrc.toString();
    }

    private TextDisplay spawn(Player player, Location location, HologramStyle style) {
        World world = location.getWorld();
        if (world == null) return null;

        try {
            TextDisplay display = world.spawn(location, TextDisplay.class, spawned -> {
                spawned.setBillboard(Display.Billboard.CENTER);
                spawned.setAlignment(TextDisplay.TextAlignment.CENTER);
                apply(spawned, style);

                spawned.setText(" ");
                DisplayCompat.setTeleportDuration(spawned, 2);
                spawned.setPersistent(false);

                spawned.setVisibleByDefault(false);
                spawned.getPersistentDataContainer()
                        .set(previewKey, PersistentDataType.STRING, TAG_VALUE);
            });

            player.showEntity(plugin, display);
            return display;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void apply(TextDisplay display, HologramStyle style) {
        display.setLineWidth(style.lineWidth());
        display.setShadowed(style.shadow());
        display.setBackgroundColor(background(style));
        display.setBrightness(style.brightness() < 0
                ? null
                : new Display.Brightness(style.brightness(), style.brightness()));

        display.setSeeThrough(true);

        float scale = SIZE_SCALE[Math.max(1, Math.min(SIZE_SCALE.length, style.size())) - 1];
        Transformation current = display.getTransformation();
        display.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f),
                current.getLeftRotation(),
                new Vector3f(scale, scale, scale),
                current.getRightRotation()));
    }

    private static boolean gone(TextDisplay display) {
        return display == null || display.isDead();
    }

    private static Color background(HologramStyle style) {
        int rgb = style.backgroundColor();
        return Color.fromARGB(Math.max(0, Math.min(255, style.backgroundOpacity())),
                (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static Location inFrontOf(Player player) {
        Location eye = player.getEyeLocation();
        if (eye.getWorld() == null) return null;

        double yaw = Math.toRadians(eye.getYaw());
        return eye.clone().add(-Math.sin(yaw) * DISTANCE, 0, Math.cos(yaw) * DISTANCE);
    }

    private void startFollowing() {
        if (follow != null || !plugin.isEnabled()) return;

        follow = Tasks.globalTimer(plugin, () -> {
            Iterator<Map.Entry<UUID, TextDisplay>> it = shown.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, TextDisplay> entry = it.next();
                Player owner = Bukkit.getPlayer(entry.getKey());
                TextDisplay display = entry.getValue();

                if (owner == null || gone(display)) {
                    Tasks.onEntity(plugin, display, display::remove);
                    it.remove();
                    continue;
                }

                Tasks.onEntity(plugin, owner, () -> {
                    Location location = inFrontOf(owner);
                    if (location != null) Tasks.teleport(display, location);
                });
            }
            stopFollowingIfIdle();
        }, FOLLOW_TICKS, FOLLOW_TICKS);
    }

    private void stopFollowingIfIdle() {
        if (follow == null || !shown.isEmpty()) return;
        follow.cancel();
        follow = null;
    }
}
