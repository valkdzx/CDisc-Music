package dev.valkdz.cdisc.api;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.player.AudioLoadResultHandler;
import dev.valkdz.cdisc.audio.player.AudioPlaylist;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.audio.source.TrackLoader;
import dev.valkdz.cdisc.audio.source.youtube.SabrResolver;
import dev.valkdz.cdisc.disc.ItemUtils;
import dev.valkdz.cdisc.feature.local.LocalTrackSettings;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class ApiDiscs {

    record Track(String address, String title, String author, long lengthMs, boolean live) {
    }

    private ApiDiscs() {
    }

    static CompletableFuture<List<Track>> resolve(String source, String title, String author, int max) {
        CompletableFuture<List<Track>> result = new CompletableFuture<>();
        PlaybackManager apm = CDiscApi.manager();
        if (apm == null) {
            result.completeExceptionally(new QueueException(QueueException.Reason.CDISC_DISABLED,
                    "CDisc is not enabled"));
            return result;
        }
        if (source == null || source.isBlank()) {
            result.completeExceptionally(new QueueException(QueueException.Reason.NO_MATCH, "source is empty"));
            return result;
        }
        String query = source.trim();
        TrackLoader loader = apm.getTrackLoader();
        String resolved = loader.resolvePlaylistQuery(query);
        if (resolved == null) {
            result.completeExceptionally(new QueueException(QueueException.Reason.NO_MATCH, "Nothing matches " + query));
            return result;
        }

        loader.loadForDisc(resolved, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                result.complete(List.of(describe(track, query, title, author)));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrack> tracks = playlist.getTracks();
                if (tracks.isEmpty()) {
                    noMatches();
                    return;
                }
                if (playlist.isSearchResult()) {
                    AudioTrack pick = playlist.getSelectedTrack() != null ? playlist.getSelectedTrack() : tracks.get(0);
                    result.complete(List.of(describe(pick, null, title, author)));
                    return;
                }
                List<Track> out = new ArrayList<>();
                for (AudioTrack track : tracks) {
                    if (out.size() >= max) break;
                    out.add(describe(track, null, null, null));
                }
                result.complete(out);
            }

            @Override
            public void noMatches() {
                result.completeExceptionally(new QueueException(QueueException.Reason.NO_MATCH,
                        "Nothing matches " + query));
            }

            @Override
            public void loadFailed(LoadException e) {
                result.completeExceptionally(new QueueException(QueueException.Reason.FAILED,
                        String.valueOf(e.getMessage()), e));
            }
        });
        return result;
    }

    private static Track describe(AudioTrack track, String query, String title, String author) {
        AudioTrackInfo info = track.getInfo();
        Main cdisc = Main.getInstance();
        String address = cdisc.getLocalMusic().addressOf(track);
        if (address == null) address = info.uri != null && !info.uri.isBlank() ? info.uri : query;
        if (address == null) address = info.identifier;

        String ownTitle = usable(info.title) ? info.title : "No name";
        String ownAuthor = usable(info.author) ? info.author : "Unknown";
        LocalTrackSettings.Shown shown = cdisc.getLocalMusic().shown(address, ownTitle, ownAuthor);
        String shownTitle = title != null ? title : shown.title() != null ? shown.title() : ownTitle;
        String shownAuthor = author != null ? author : shown.author() != null ? shown.author() : ownAuthor;

        long length = info.isStream || info.length == AudioTrackInfo.UNKNOWN_LENGTH ? 0 : info.length;
        return new Track(address, nfc(shownTitle), nfc(shownAuthor), length, info.isStream);
    }

    private static boolean usable(String tag) {
        return tag != null && !tag.isBlank()
                && !tag.equals(AudioTrackInfo.UNKNOWN_TITLE) && !tag.equals(AudioTrackInfo.UNKNOWN_ARTIST);
    }

    private static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    static Material material(Main plugin) {
        Material material = Material.matchMaterial(plugin.cdiscConfig().getApiDiscMaterial().trim());
        return material != null && ItemUtils.isDisc(new ItemStack(material)) ? material : Material.MUSIC_DISC_13;
    }

    static ItemStack item(Track track, Material material, boolean locked) {
        ItemStack item = new ItemStack(material);
        ItemUtils.Hint hint = hintFor(track);
        ItemUtils.saveTrackToDisc(null, item, track.address(), null, track.title(), track.author(), null, hint);
        if (locked) ItemUtils.lock(item);
        return item;
    }

    // CDisc trusts a YouTube disc's hint as a playback route, so a YouTube disc carries
    // only the route CDisc itself recorded; anything else may carry just its length.
    private static ItemUtils.Hint hintFor(Track track) {
        String videoId = SabrResolver.videoIdOf(track.address());
        PlaybackManager apm = CDiscApi.manager();
        if (videoId != null) return apm == null ? null : apm.getTrackLoader().hintFor(videoId);
        return new ItemUtils.Hint(null, track.lengthMs(), track.live(), null, System.currentTimeMillis(), null);
    }

    static Optional<DiscInfo> read(ItemStack item) {
        ItemUtils.DiscData data = ItemUtils.readDiscData(item);
        if (data == null || !ItemUtils.isDisc(item)) return Optional.empty();
        ItemUtils.Hint hint = data.hint();
        return Optional.of(new DiscInfo(data.query(), data.title(), data.author(),
                hint == null ? 0 : hint.lengthMs(), hint != null && hint.live(), ItemUtils.isLocked(item)));
    }
}
