package dev.valkdz.cdisc.audio.sabr;

import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioPlaylist;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.LazyTrack;
import dev.valkdz.cdisc.audio.player.LoadException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Searches and playlists; a single video is read by SabrResolver when it starts.
public final class YouTubeSourceManager implements AudioSourceManager {

    private static final int SEARCH_LIMIT = 10;
    private static final Pattern LIST = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");

    private final YouTubeSearch search;
    private final YouTubePlaylist playlists;
    private final Supplier<String> visitorData;
    private final LazyTrack.Resolver resolver;

    public YouTubeSourceManager(YouTubeSearch search, YouTubePlaylist playlists, Supplier<String> visitorData,
                                LazyTrack.Resolver resolver) {
        this.search = search;
        this.playlists = playlists;
        this.visitorData = visitorData;
        this.resolver = resolver;
    }

    @Override
    public String getSourceName() {
        return "youtube";
    }

    @Override
    public AudioItem loadItem(String identifier) {
        try {
            if (identifier.startsWith("ytsearch:")) return search(identifier.substring(9).trim());
            if (!identifier.contains("youtube.com") && !identifier.contains("youtu.be")) return null;

            Matcher list = LIST.matcher(identifier);
            return list.find() ? playlist(list.group(1), SabrResolver.videoIdOf(identifier)) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoadException("Interrupted while asking YouTube", e);
        } catch (IOException e) {
            throw new LoadException("Loading from YouTube failed: " + e.getMessage(), e);
        }
    }

    private AudioItem search(String query) throws IOException, InterruptedException {
        if (query.isEmpty()) return AudioItem.NONE;
        List<AudioTrack> tracks = new ArrayList<>();
        for (YouTubeSearch.Result result : search.search(query, visitorData.get(), SEARCH_LIMIT)) {
            tracks.add(entry(result.videoId(), result.title(), result.channel(), result.durationSeconds()));
        }
        return tracks.isEmpty() ? AudioItem.NONE : new AudioPlaylist("Search results for: " + query, tracks, null, true);
    }

    private AudioItem playlist(String listId, String videoId) throws IOException {
        YouTubePlaylist.Contents contents = playlists.read(listId, videoId, visitorData.get());
        List<AudioTrack> tracks = new ArrayList<>();
        AudioTrack selected = null;
        for (YouTubePlaylist.Entry entry : contents.entries()) {
            AudioTrack track = entry(entry.videoId(), entry.title(), entry.channel(), entry.durationSeconds());
            if (entry.videoId().equals(videoId)) selected = track;
            tracks.add(track);
        }
        return tracks.isEmpty() ? AudioItem.NONE : new AudioPlaylist(contents.name(), tracks, selected, false);
    }

    private AudioTrack entry(String videoId, String title, String channel, long seconds) {
        return new LazyTrack(new AudioTrackInfo(
                title == null ? AudioTrackInfo.UNKNOWN_TITLE : title,
                channel == null ? AudioTrackInfo.UNKNOWN_ARTIST : channel,
                seconds > 0 ? seconds * 1000 : AudioTrackInfo.UNKNOWN_LENGTH,
                videoId, seconds <= 0, "https://www.youtube.com/watch?v=" + videoId,
                "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg", null), this, resolver);
    }
}
