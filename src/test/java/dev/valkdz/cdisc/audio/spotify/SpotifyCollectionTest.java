package dev.valkdz.cdisc.audio.spotify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpotifyCollectionTest {

    @Test
    void recognisesPlaylistsAndAlbumsButNotTracks() {
        assertEquals("playlist/37i9dQZF1DXcBWIGoYBM5M", SpotifyBridge.collectionOf(
                "https://open.spotify.com/intl-ru/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"));
        assertEquals("album/4aawyAB9vmqN3uQ7FjRGTy",
                SpotifyBridge.collectionOf("spotify:album:4aawyAB9vmqN3uQ7FjRGTy"));
        assertNull(SpotifyBridge.collectionOf("https://open.spotify.com/track/3h5T5JypYU7huFiVYhv1dr"));
        assertNull(SpotifyBridge.collectionOf("https://www.youtube.com/playlist?list=PLabc"));
    }

    @Test
    void readsTheEmbedTrackList() throws Exception {
        String html = "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{\"name\":\"Hits\","
                + "\"trackList\":[{\"uri\":\"spotify:track:3h5T5JypYU7huFiVYhv1dr\",\"title\":\"BbY WOW\","
                + "\"subtitle\":\"KAROL G, Judeline\",\"duration\":225834},"
                + "{\"uri\":\"spotify:episode:xyz\",\"title\":\"skip\"}]}}}}}}</script></html>";

        SpotifyBridge.Collection read = SpotifyBridge.parseEmbed(html);

        assertEquals("Hits", read.name());
        assertEquals(1, read.entries().size());
        assertEquals("KAROL G, Judeline", read.entries().get(0).artist());
        assertEquals("https://open.spotify.com/track/3h5T5JypYU7huFiVYhv1dr",
                read.entries().get(0).spotifyUrl());
    }
}
