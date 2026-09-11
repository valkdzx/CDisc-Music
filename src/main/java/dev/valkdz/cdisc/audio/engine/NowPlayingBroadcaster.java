package dev.valkdz.cdisc.audio.engine;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Chat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.text.Normalizer;
import java.util.function.Predicate;

public class NowPlayingBroadcaster {

    private static final int[] GRADIENT = {
            0x4CEB72, 0x7B1C76, 0x2200C2, 0x2645D7, 0x4CEB72
    };
    private static final int TOTAL_TICKS = 60;

    private final Main plugin;

    private static int channel(int from, int shift, int to, float ratio) {
        int a = (from >> shift) & 0xFF;
        int b = (to >> shift) & 0xFF;
        return (int) (a + ratio * (b - a));
    }

    public NowPlayingBroadcaster(Main plugin) {
        this.plugin = plugin;
    }

    public void broadcast(Block block, Location origin, AudioTrack track, String discTitle, String discAuthor,
                          int hearDistance, Predicate<Block> stillActive) {
        String rawAuthor = discAuthor != null ? discAuthor
                : (track.getInfo().author != null ? track.getInfo().author : "Unknown");
        String rawTitle = discTitle != null ? discTitle
                : (track.getInfo().title != null ? track.getInfo().title : "No name");
        String author = Normalizer.normalize(rawAuthor, Normalizer.Form.NFC);
        String title = Normalizer.normalize(rawTitle, Normalizer.Form.NFC);

        int transitions = GRADIENT.length - 1;
        int ticksPerTransition = TOTAL_TICKS / transitions;

        for (Player p : Bukkit.getOnlinePlayers()) {

            if (!dev.valkdz.cdisc.util.PlayerPrefs.showsTrackMessages(p)) continue;

            String msg = plugin.getMessageManager().get(p, "actionbar.playing", author, title);
            if (msg == null) msg = " ";

            if (!p.getWorld().equals(origin.getWorld())) continue;
            if (p.getLocation().distanceSquared(origin) > (double) hearDistance * hearDistance) continue;

            String finalMsg = msg;
            new BukkitRunnable() {
                int tick = 0;

                @Override
                public void run() {
                    if (tick >= TOTAL_TICKS || !stillActive.test(block)) {
                        Chat.clearActionBar(p);
                        cancel();
                        return;
                    }

                    int segment = Math.min(tick / ticksPerTransition, transitions - 1);
                    int start = GRADIENT[segment];
                    int end = GRADIENT[segment + 1];
                    float ratio = (tick % ticksPerTransition) / (float) ticksPerTransition;

                    int r = channel(start, 16, end, ratio);
                    int g = channel(start, 8, end, ratio);
                    int b = channel(start, 0, end, ratio);

                    Chat.actionBar(p, finalMsg != null ? finalMsg : " ", r, g, b);
                    tick++;
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }
    }
}
