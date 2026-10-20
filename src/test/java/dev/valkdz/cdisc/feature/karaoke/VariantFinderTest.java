package dev.valkdz.cdisc.feature.karaoke;

import dev.valkdz.cdisc.feature.karaoke.VariantFinder.Hit;
import dev.valkdz.cdisc.feature.karaoke.VariantFinder.Kind;
import dev.valkdz.cdisc.feature.karaoke.VariantFinder.Song;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantFinderTest {

    private static final Song DYNASTY = Song.of("VILLIAN, madk1d - ДИНАСТИЯ (official music video)",
            "VILLIAN and madk1d", 165_000, "https://www.youtube.com/watch?v=J7NFL-eOxiQ");

    @Test
    void splitsArtistFromTheSongAndDropsTheVideoNoise() {
        assertEquals("VILLIAN, madk1d", DYNASTY.artist());
        assertEquals("ДИНАСТИЯ", DYNASTY.name());

        Song plain = Song.of("Группа крови", "Кино", 0, null);
        assertEquals("Кино", plain.artist());
        assertEquals("Группа крови", plain.name());
    }

    @Test
    void aSongBySeveralArtistsIsAlsoSearchedUnderEachOfThem() {
        Song spotify = Song.of("ДИНАСТИЯ", "VILLIAN, madk1d", 151_000, "https://open.spotify.com/track/x");

        assertEquals(List.of("VILLIAN madk1d ДИНАСТИЯ", "VILLIAN ДИНАСТИЯ", "madk1d ДИНАСТИЯ"), VariantFinder.bases(spotify));
        assertTrue(VariantFinder.queries(spotify, Kind.MINUS, VariantFinder.YOUTUBE).contains("madk1d ДИНАСТИЯ минус"));
        assertEquals(List.of("Кино Группа крови"), VariantFinder.bases(Song.of("Группа крови", "Кино", 0, null)));
        assertEquals(List.of("Band feat. Guest Song", "Band Song", "Guest Song"),
                VariantFinder.bases(Song.of("Song", "Band feat. Guest", 0, null)));
    }

    @Test
    void aMarkedMinusIsReadFirstAndARemakeNever() {
        double marked = score(Kind.MINUS, yt("[Instrumental] VILLIAN ft. madk1d - ДИНАСТИЯ [Official Instrumental]", 165));
        double unmarked = score(Kind.MINUS, yt("VILLIAN, madk1d - ДИНАСТИЯ (official music video)", 165));
        assertTrue(score(Kind.MINUS, yt("[Минус / Instrumental] VILLIAN, madk1d – ДИНАСТИЯ", 151)) > 0);
        assertTrue(unmarked > 0 && unmarked < marked);
        assertTrue(score(Kind.MINUS, yt("VILLIAN, madk1d - ДИНАСТИЯ (Perfect Loop) instrumental", 176)) < 0);
        assertTrue(score(Kind.MINUS, yt("ДИНАСТИЯ instrumental slowed + reverb", 170)) < 0);
        assertTrue(score(Kind.MINUS, yt("VILLIAN, madk1d - ДИНАСТИЯ INSTRUMENTAL (remake by @sonirols)", 160)) < 0);
        assertTrue(score(Kind.MINUS, yt("Кино - Группа крови минус", 280)) < 0);
    }

    @Test
    void aCutNamedForAnotherPartIsReadLast() {
        Song song = Song.of("Супермаркет", "Пошлая Молли", 214_000, "https://www.youtube.com/watch?v=TNLfQTlHLKc");
        double karaoke = VariantFinder.score(song, Kind.MINUS, yt("Пошлая Молли - Супермаркет (Караоке)", 214));

        assertTrue(VariantFinder.score(song, Kind.MINUS, yt("Пошлая Молли - Супермаркет (guitar backing track)", 214)) < karaoke);
        assertTrue(VariantFinder.score(song, Kind.MINUS, yt("Пошлая Молли - Супермаркет (Акапелла / Acapella)", 214)) < karaoke);
        assertTrue(VariantFinder.score(song, Kind.MINUS, yt("Пошлая Молли - Супермаркет (backing track)", 214)) > 0);
        assertTrue(VariantFinder.queries(song, Kind.MINUS, VariantFinder.YOUTUBE).containsAll(List.of(
                "Пошлая Молли Супермаркет минус", "Пошлая Молли Супермаркет instrumental", "Пошлая Молли Супермаркет караоке",
                "Супермаркет минус")));
    }

    @Test
    void anUploadersTitleStillGivesTheArtistAndTheSong() {
        Song upload = Song.of("ПОШЛАЯ МОЛЛИ –СУПЕРМАРКЕТ  [320 kbps]", "ХАРТ_hack", 215_000, null);

        assertEquals("ПОШЛАЯ МОЛЛИ", upload.artist());
        assertEquals("СУПЕРМАРКЕТ", upload.name());
        assertEquals("Ки-ки", Song.of("Ки-ки", "Band", 0, null).name());
    }

    @Test
    void uncensoredTakesAnyUploadButRanksAMarkerHigher() {
        assertTrue(score(Kind.UNCENSORED, sc("ДИНАСТИЯ", "VILLIAN", 151)) > 0);
        assertTrue(score(Kind.UNCENSORED, sc("ДИНАСТИЯ (Instrumental)", "VILLIAN", 151)) < 0);
        assertTrue(score(Kind.UNCENSORED, yt("VILLIAN, madk1d - ДИНАСТИЯ (без цензуры)", 151)) > 0);
        assertTrue(score(Kind.UNCENSORED, yt("VILLIAN, madk1d - ДИНАСТИЯ", 151)) > 0);
        assertTrue(score(Kind.UNCENSORED, yt("VILLIAN, madk1d - ДИНАСТИЯ (без цензуры)", 151))
                > score(Kind.UNCENSORED, yt("VILLIAN, madk1d - ДИНАСТИЯ", 151)));
        assertTrue(score(Kind.UNCENSORED, yt("[БЕЗ МАТА] ДИНАСТИЯ - VILLIAN, madk1d | Лучшая версия без цензуры", 153)) < 0);
        assertTrue(score(Kind.UNCENSORED, sc("ДИНАСТИЯ (clean)", "VILLIAN", 151)) < 0);
        assertTrue(score(Kind.UNCENSORED, sc("ДИНАСТИЯ цензурная версия", "VILLIAN", 151)) < 0);
        assertTrue(score(Kind.UNCENSORED, sc("ДИНАСТИЯ нецензурная версия", "VILLIAN", 151)) > 0);
    }

    @Test
    void lengthMustBeClose() {
        assertTrue(score(Kind.UNCENSORED, sc("ДИНАСТИЯ", "VILLIAN", 190)) < 0);
        assertTrue(score(Kind.UNCENSORED, sc("ДИНАСТИЯ", "VILLIAN", 80)) < 0);
        assertTrue(score(Kind.UNCENSORED, sc("ДИНАСТИЯ", "VILLIAN", 160)) > score(Kind.UNCENSORED, sc("ДИНАСТИЯ", "VILLIAN", 120)));
    }

    @Test
    void theOriginalItselfIsNeverAVariant() {
        Hit self = new Hit(VariantFinder.YOUTUBE, DYNASTY.uri(), "VILLIAN, madk1d - ДИНАСТИЯ instrumental", "VILLIAN", 165_000);
        assertTrue(score(Kind.MINUS, self) < 0);
    }

    @Test
    void soundCloudGoesFirstForUncensoredAndYoutubeFirstForMinus() throws Exception {
        Map<String, List<Hit>> results = Map.of(
                VariantFinder.YOUTUBE, List.of(yt("VILLIAN, madk1d - ДИНАСТИЯ (без цензуры) official", 151),
                        yt("VILLIAN, madk1d - ДИНАСТИЯ instrumental", 151)),
                VariantFinder.SOUNDCLOUD, List.of(sc("ДИНАСТИЯ", "someone", 151),
                        sc("ДИНАСТИЯ instrumental", "VILLIAN", 151)));
        VariantFinder finder = new VariantFinder(List.of(fake(VariantFinder.YOUTUBE, results), fake(VariantFinder.SOUNDCLOUD, results)));

        List<VariantFinder.Candidate> clean = finder.find(DYNASTY, Kind.UNCENSORED, 5);
        assertEquals(VariantFinder.SOUNDCLOUD, clean.get(0).hit().source());
        assertEquals(VariantFinder.YOUTUBE, clean.get(clean.size() - 1).hit().source());

        List<VariantFinder.Candidate> minus = finder.find(DYNASTY, Kind.MINUS, 5);
        assertEquals(VariantFinder.YOUTUBE, minus.get(0).hit().source());
        assertEquals(VariantFinder.SOUNDCLOUD, minus.get(1).hit().source());
        assertEquals("VILLIAN, madk1d - ДИНАСТИЯ (без цензуры) official", minus.get(minus.size() - 1).hit().title());
    }

    private static double score(Kind kind, Hit hit) {
        return VariantFinder.score(DYNASTY, kind, hit);
    }

    private static Hit yt(String title, int seconds) {
        return new Hit(VariantFinder.YOUTUBE, "yt:" + title, title, "somebody", seconds * 1000L);
    }

    private static Hit sc(String title, String author, int seconds) {
        return new Hit(VariantFinder.SOUNDCLOUD, "sc:" + title + author + seconds, title, author, seconds * 1000L);
    }

    private static VariantFinder.Source fake(String name, Map<String, List<Hit>> results) {
        return new VariantFinder.Source() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<Hit> search(String query) {
                return results.get(name);
            }
        };
    }
}
