package dev.valkdz.cdisc.feature.lyrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsQueryTest {

    private static final LyricsQuery DOM_PERIGNON = LyricsQuery.of("Poshlaya Molly, Eldzhey", "Дом Периньон", 174_000);

    @Test
    void anotherSongOfTheSameLengthIsNeverTaken() {
        assertEquals(-1, DOM_PERIGNON.rank("Первый снег", "Перемотка", 5));
        assertEquals(-1, DOM_PERIGNON.rank("Розовое вино", "Элджей, Feduk", 0));
    }

    @Test
    void theSongIsFoundUnderItsArtistsInEitherAlphabet() {
        long credited = DOM_PERIGNON.rank("Дом Периньон", "Пошлая Молли, Элджей", 0);
        long cover = DOM_PERIGNON.rank("Дом периньон", "Brunello", 0);

        assertEquals(0, credited);
        assertTrue(cover > credited);
        assertTrue(DOM_PERIGNON.creditsMatch("Элджей"));
        assertFalse(DOM_PERIGNON.creditsMatch("Перемотка"));
    }

    @Test
    void theTitleMatchesDespiteCreditsAndNotes() {
        LyricsQuery song = LyricsQuery.of("Band", "Song", 200_000);

        assertTrue(song.titleMatches("Song (feat. Guest) [Remastered]"));
        assertTrue(song.titleMatches("SONG"));
        assertFalse(song.titleMatches("Songbird"));
        assertTrue(DOM_PERIGNON.titleMatches("Dom Perinon"));
    }

    @Test
    void anUploadersTitleStillGivesTheArtistAndTheSong() {
        LyricsQuery upload = LyricsQuery.of("ХАРТ_hack", "ПОШЛАЯ МОЛЛИ –СУПЕРМАРКЕТ  [320 kbps]", 215_000);

        assertEquals("ПОШЛАЯ МОЛЛИ", upload.artist());
        assertEquals("СУПЕРМАРКЕТ", upload.track());
        assertEquals("ХАРТ_hack", upload.fallback().artist());
        assertTrue(upload.titleMatches("Супермаркет"));
        assertTrue(upload.creditsMatch("Пошлая Молли"));
        assertEquals("Ки-ки", LyricsQuery.of("Band", "Ки-ки", 0).track());
    }

    @Test
    void theFirstArtistLeadsTheSearch() {
        assertEquals("Poshlaya Molly", DOM_PERIGNON.primaryArtist());
        assertEquals("Band", LyricsQuery.of("Band feat. Guest", "Song", 0).primaryArtist());
        assertEquals("Кино", LyricsQuery.of("Кино", "Группа крови", 0).primaryArtist());
    }

    @Test
    void withoutALengthTheArtistMustMatch() {
        LyricsQuery live = LyricsQuery.of("Кино", "Группа крови", 0);

        assertEquals(-1, live.rank("Группа крови", "Someone", 0));
        assertEquals(0, live.rank("Группа крови", "Kino", 0));
    }
}
