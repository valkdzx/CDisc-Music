package dev.valkdz.cdisc.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Chat;
import dev.valkdz.cdisc.util.TimeUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SearchResults {

    private static final long EXPIRY_MS = 3 * 60 * 1000L;

    private static final UUID CONSOLE = new UUID(0L, 0L);

    private final Main plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public enum Kind {

        DISC,

        DOWNLOAD
    }

    private record Pending(List<Entry> entries, long shownAt, Kind kind) {
        boolean expired() {
            return System.currentTimeMillis() - shownAt > EXPIRY_MS;
        }
    }

    public record Entry(AudioTrack track, String title, String address) {
    }

    public SearchResults(Main plugin) {
        this.plugin = plugin;
    }

    public void show(Player player, String query, List<Entry> entries) {
        show(player, query, entries, Kind.DISC);
    }

    public void show(CommandSender sender, String query, List<Entry> entries, Kind kind) {
        if (entries.isEmpty()) return;

        pending.values().removeIf(Pending::expired);

        pending.put(keyOf(sender),
                new Pending(List.copyOf(entries), System.currentTimeMillis(), kind));

        sender.sendMessage("§8§m                                        ");
        sender.sendMessage("§e" + message(sender, "search.header",
                String.valueOf(entries.size()), query));

        for (int i = 0; i < entries.size(); i++) {
            String text = "§a" + entry(sender, entries.get(i), i + 1);
            if (sender instanceof Player player) {
                Chat.send(player, Chat.command(text, "/cdisc pick " + (i + 1),
                        message(sender, hoverKey(kind))));
            } else {
                sender.sendMessage(text + "  §8/cdisc pick " + (i + 1));
            }
        }

        sender.sendMessage("§7" + message(sender, hintKey(sender, kind)));
        sender.sendMessage("§8§m                                        ");
    }

    private static UUID keyOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE;
    }

    private static String hoverKey(Kind kind) {
        return kind == Kind.DOWNLOAD ? "search.hover_download" : "search.hover";
    }

    private static String hintKey(CommandSender sender, Kind kind) {
        if (!(sender instanceof Player)) return "search.hint_console";
        return kind == Kind.DOWNLOAD ? "search.hint_download" : "search.hint";
    }

    private String entry(CommandSender sender, Entry entry, int number) {
        AudioTrack track = entry.track();
        String title = entry.title() != null ? entry.title() : "No name";
        String author = track.getInfo().author != null ? track.getInfo().author : "Unknown";
        String length = track.getInfo().isStream
                ? message(sender, "command.player.info_live")
                : TimeUtils.format(track.getInfo().length);

        return message(sender, "search.entry",
                String.valueOf(number),
                trim(Normalizer.normalize(title, Normalizer.Form.NFC), 45),
                trim(Normalizer.normalize(author, Normalizer.Form.NFC), 25),
                length);
    }

    private String message(CommandSender sender, String path, Object... args) {
        return plugin.getMessageManager()
                .get(sender instanceof Player player ? player : null, path, args);
    }

    public record Pick(AudioTrack track, String address, Kind kind) {
    }

    public Pick claim(CommandSender sender, int number) {
        UUID key = keyOf(sender);
        Pending waiting = pending.get(key);
        if (waiting == null || waiting.expired()) {
            pending.remove(key);
            return null;
        }
        if (number < 1 || number > waiting.entries().size()) return null;

        pending.remove(key);
        Entry chosen = waiting.entries().get(number - 1);
        return new Pick(chosen.track(), chosen.address(), waiting.kind());
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
