package dev.valkdz.cdisc.audio.spotify;

import dev.valkdz.cdisc.util.Json;
import dev.valkdz.cdisc.audio.sabr.YouTubeSearch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpotifyTrackTest {

    @Test
    void readsTheRawTrackTheBackendPassesOn() throws Exception {
        String json = "{\"artists\":[{\"id\":\"0gxyHStUsqpMadRV0Di1Qt\",\"name\":\"Rick Astley\"}],"
                + "\"duration_ms\":213573,\"external_ids\":{\"isrc\":\"GBARL9300135\"},"
                + "\"id\":\"4cOdK2wGLETKBW3PvgPWqT\",\"name\":\"Never Gonna Give You Up\"}";

        SpotifyBridge.Wanted wanted = SpotifyBridge.wantedOf(Json.parse(json));

        assertEquals("GBARL9300135", wanted.isrc());
        assertEquals("Never Gonna Give You Up", wanted.title());
        assertEquals("Rick Astley", wanted.artist());
        assertEquals(213573, wanted.durationMs());
    }

    @Test
    void aTrackWithoutIsrcIsSearchedByNameOnly() throws Exception {
        SpotifyBridge.Wanted wanted = SpotifyBridge.wantedOf(Json.parse(
                "{\"name\":\"x\",\"artists\":[],\"external_ids\":{}}"));

        assertNull(wanted.isrc());
        assertEquals("", wanted.artist());
    }

    @Test
    void picksTheTopicUploadWithinTheLength() {
        SpotifyBridge.Wanted wanted = new SpotifyBridge.Wanted(null, "Song", "Band", 200_000);

        assertEquals("topic", SpotifyBridge.pick(List.of(
                new YouTubeSearch.Result("clip", "Band - Song (Official Video)", "Band", 201),
                new YouTubeSearch.Result("long", "Song", "Band - Topic", 260),
                new YouTubeSearch.Result("topic", "Song", "Band - Topic", 203)), wanted));

        assertNull(SpotifyBridge.pick(List.of(
                new YouTubeSearch.Result("other", "Unrelated", "Someone", 200)), wanted));
    }
}
