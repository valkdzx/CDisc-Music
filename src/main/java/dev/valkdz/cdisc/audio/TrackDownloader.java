package dev.valkdz.cdisc.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.engine.TrackLoader;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.Normalizer;
import java.util.List;

public final class TrackDownloader {

    private final Main plugin;

    public TrackDownloader(Main plugin) {
        this.plugin = plugin;
    }

    public void download(CommandSender sender, String query, String desiredName) {
        SearchQuery parsed = SearchQuery.parse(query);
        TrackLoader loader = plugin.getAudioPlayerManager().getTrackLoader();

        if (LocalMusicLibrary.isLocalQuery(parsed.text())) {
            sender.sendMessage("§c" + message(sender, "download.already_local"));
            return;
        }

        String resolved = loader.resolveQuery(parsed.text());
        if (resolved == null) {
            sender.sendMessage("§c" + message(sender, "lavaplayer.track.invalid_query"));
            return;
        }

        if (TrackLoader.isSearch(resolved)) {
            int ceiling = plugin.cdiscConfig().getSearchMaxResults(
                    TrackLoader.sourceIdOf(parsed.text()));
            int wanted = parsed.requestedResults() == SearchQuery.UNSET
                    ? plugin.cdiscConfig().getSearchDefaultResults()
                    : parsed.requestedResults();

            search(sender, parsed.text(), resolved, Math.min(wanted, ceiling));
            return;
        }

        fetch(sender, parsed.text(), resolved, desiredName);
    }

    private void fetch(CommandSender sender, String query, String resolved, String desiredName) {
        sender.sendMessage("§e" + message(sender, "download.started"));

        plugin.getAudioPlayerManager().getTrackLoader()
                .load(resolved, null, null, null, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) {
                save(sender, track, resolved, desiredName);
            }

            @Override public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrack> tracks = playlist.getTracks();
                if (tracks.isEmpty()) {
                    noMatches();
                    return;
                }
                trackLoaded(tracks.get(0));

                int rest = tracks.size() - 1;
                if (rest > 0) {
                    onServerThread(() -> sender.sendMessage("§e" + message(sender,
                            "playlist.truncated", String.valueOf(rest))));
                }
            }

            @Override public void noMatches() {
                String hint = LoadDiagnosis.explain(plugin, asPlayer(sender), query);
                onServerThread(() -> {
                    sender.sendMessage("§c" + message(sender, "lavaplayer.track.notfound"));
                    if (hint != null) sender.sendMessage("§7" + hint);
                });
            }

            @Override public void loadFailed(FriendlyException e) {
                onServerThread(() -> sender.sendMessage("§c" + message(sender,
                        "lavaplayer.track.error", String.valueOf(e.getMessage()))));
            }
        });
    }

    private void search(CommandSender sender, String query, String resolved, int limit) {
        sender.sendMessage("§e" + message(sender, "lavaplayer.track.loading"));

        plugin.getAudioPlayerManager().getTrackLoader()
                .loadItem(resolved, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) {

                offer(track);
            }

            @Override public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrack> results = playlist.getTracks();
                if (results.isEmpty()) {
                    noMatches();
                    return;
                }
                if (results.size() == 1) {
                    offer(results.get(0));
                    return;
                }

                List<SearchResults.Entry> shown = results.stream()
                        .limit(limit)
                        .map(found -> new SearchResults.Entry(
                                found, found.getInfo().title, addressOf(found, query)))
                        .toList();

                onServerThread(() -> plugin.getSearchResults()
                        .show(sender, query, shown, SearchResults.Kind.DOWNLOAD));
            }

            private void offer(AudioTrack only) {
                onServerThread(() -> pick(sender, only, addressOf(only, query), null));
            }

            @Override public void noMatches() {
                String hint = LoadDiagnosis.explain(plugin, asPlayer(sender), query);
                onServerThread(() -> {
                    sender.sendMessage("§c" + message(sender, "lavaplayer.track.notfound"));
                    if (hint != null) sender.sendMessage("§7" + hint);
                });
            }

            @Override public void loadFailed(FriendlyException e) {
                onServerThread(() -> sender.sendMessage("§c" + message(sender,
                        "lavaplayer.track.error", String.valueOf(e.getMessage()))));
            }
        });
    }

    public void pick(CommandSender sender, AudioTrack track, String address, String desiredName) {
        String name = desiredName != null ? desiredName : nameOf(track.getInfo());
        fetch(sender, address, address, name);
    }

    private void save(CommandSender sender, AudioTrack track, String address, String desiredName) {

        if (track.getInfo().isStream) {
            onServerThread(() -> sender.sendMessage("§c" + message(sender, "download.is_stream")));
            return;
        }

        String name = desiredName != null ? desiredName : nameOf(track.getInfo());
        String backend = plugin.getAudioPlayerManager().getTrackLoader()
                .backendDownloadUrlFor(address);

        plugin.getLocalDownloader().download(track, backend, name, result -> {
            if (result.ok()) {
                sender.sendMessage("§a" + message(sender, "download.done", result.detail()));
                sender.sendMessage("§7" + message(sender, "download.hint",
                        LocalMusicLibrary.PREFIX + result.detail()));
                return;
            }
            sender.sendMessage("§c" + message(sender, failureKey(result.status()),
                    result.detail() == null ? "?" : result.detail()));
        });
    }

    public static String failureKey(LocalDownloader.Status status) {
        return switch (status) {
            case DISABLED -> "download.disabled";
            case BAD_URL -> "download.bad_url";
            case BLOCKED_ADDRESS -> "download.blocked_address";
            case HTTP_ERROR -> "download.http_error";
            case TOO_LARGE -> "download.too_large";
            case BAD_TYPE -> "download.bad_type";
            case NAME_TAKEN -> "download.name_taken";
            case NO_STREAM -> "download.no_stream";
            default -> "download.io_error";
        };
    }

    private static String nameOf(AudioTrackInfo info) {
        String title = usable(info.title) ? info.title.trim() : null;
        String author = usable(info.author) ? info.author.trim() : null;

        if (title == null) return null;
        String name = author == null ? title : author + " - " + title;

        name = name.replace('/', '-').replace('\\', '-');
        return Normalizer.normalize(name, Normalizer.Form.NFC);
    }

    private static boolean usable(String tag) {
        return tag != null && !tag.isBlank()
                && !tag.equalsIgnoreCase("Unknown")
                && !tag.equalsIgnoreCase("Unknown title")
                && !tag.equalsIgnoreCase("Unknown artist");
    }

    private static String addressOf(AudioTrack track, String query) {
        String uri = track.getInfo().uri;
        return uri == null || uri.isBlank() ? query : uri;
    }

    private static Player asPlayer(CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }

    private String message(CommandSender sender, String path, Object... args) {
        return plugin.getMessageManager().get(asPlayer(sender), path, args);
    }

    private void onServerThread(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }
}
