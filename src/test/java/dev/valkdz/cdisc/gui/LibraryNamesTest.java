package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.feature.library.TrackLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LibraryNamesTest {

    private static final TrackLibrary.Track SUPERMARKET = track("Супермаркет", "Пошлая Молли");
    private static final TrackLibrary.Track SUPER = track("Super", "Band");
    private static final TrackLibrary.Track SUPERNOVA = track("Supernova", "Band");

    @Test
    void aTrackIsNamedByItsArtistAndTitle() {
        assertEquals("Пошлая Молли - Супермаркет", SUPERMARKET.name());
        assertEquals("Song", track("Song", "").name());
    }

    @Test
    void aNameOrAnUnambiguousStartFindsTheTrack() {
        List<TrackLibrary.Track> tracks = List.of(SUPERMARKET, SUPER, SUPERNOVA);

        assertEquals(SUPERMARKET, LibraryGuiManager.named(tracks, "пошлая молли - супер"));
        assertEquals(SUPER, LibraryGuiManager.named(tracks, "Band - Super"));
        assertEquals(SUPERNOVA, LibraryGuiManager.named(tracks, "band - supern"));
        assertNull(LibraryGuiManager.named(tracks, "Band - Sup"));
        assertNull(LibraryGuiManager.named(tracks, "Кино"));
    }

    @Test
    void aStarredTrackShowsTheRecordItCameFrom() {
        assertEquals(org.bukkit.Material.MUSIC_DISC_CAT, LibraryGuiManager.discOf(onDisc("MUSIC_DISC_CAT")));
        for (String other : new String[]{null, "STONE", "MUSIC_DISC_FROM_A_LATER_VERSION"}) {
            assertEquals(true, LibraryGuiManager.discOf(onDisc(other)).name().startsWith("MUSIC_DISC_"), other);
        }
    }

    private static TrackLibrary.Track onDisc(String disc) {
        return new TrackLibrary.Track("https://example.com/cat", null, "cat", "C418", null, 185_000, false, 0, disc);
    }

    private static TrackLibrary.Track track(String title, String author) {
        return new TrackLibrary.Track("https://example.com/" + title, null, title, author, null, 200_000, false, 0, null);
    }
}
