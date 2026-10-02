package dev.valkdz.cdisc.audio.source.spotify;

import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioPlaylist;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.LazyTrack;
import dev.valkdz.cdisc.audio.player.LoadException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Searches Spotify with the app's own keys; links are read by SpotifyBridge before any source is asked.
public final class SpotifySourceManager implements AudioSourceManager {

    private static final int SEARCH_LIMIT = 10;

    private final SpotifyBridge bridge;
    private final LazyTrack.Resolver resolver;

    public SpotifySourceManager(SpotifyBridge bridge, LazyTrack.Resolver resolver) {
        this.bridge = bridge;
        this.resolver = resolver;
    }

    @Override
    public String getSourceName() {
        return "spotify";
    }

    @Override
    public AudioItem loadItem(String identifier) {
        if (!identifier.startsWith("spsearch:") || !bridge.canSearch()) return null;
        String query = identifier.substring(9).trim();
        if (query.isEmpty()) return AudioItem.NONE;

        try {
            List<AudioTrack> tracks = new ArrayList<>();
            for (SpotifyBridge.Entry entry : bridge.search(query, SEARCH_LIMIT)) {
                tracks.add(new SpotifyEntryTrack(entry, resolver));
            }
            return tracks.isEmpty() ? AudioItem.NONE
                    : new AudioPlaylist("Search results for: " + query, tracks, null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoadException("Interrupted while searching Spotify", e);
        } catch (IOException e) {
            throw new LoadException("Searching Spotify failed: " + e.getMessage(), e);
        }
    }
}
