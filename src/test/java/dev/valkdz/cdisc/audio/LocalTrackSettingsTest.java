package dev.valkdz.cdisc.audio;

import dev.valkdz.cdisc.lyrics.SyncedLyrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTrackSettingsTest {

    private static final String SAMPLE = """
            {
                "name": "GreatMusic",
                "default-volume": 70,
                "permissions": "some.group", //or !some.group
                "lyrics": {
                    "enabled": true,
                    "sync-with-time": true, //if false, lines go show without time sync of music
                    "lines": {
                        "00:01": "First",
                        "00:03": "Second",
                    }
                }
            }
            """;

    @Test
    @DisplayName("the documented example reads, comments and trailing comma included")
    void readsSample() throws Exception {
        LocalTrackSettings settings = LocalTrackSettings.parse(SAMPLE);

        assertEquals("GreatMusic", settings.name());
        assertEquals(70, settings.volume());
        assertEquals(List.of("some.group"), settings.permissions());
        assertTrue(settings.lyricsEnabled());
        assertTrue(settings.syncWithTime());
        assertEquals(List.of(new LocalTrackSettings.Line(1000L, "First"),
                new LocalTrackSettings.Line(3000L, "Second")), settings.lines());
    }

    @Test
    @DisplayName("what is written reads back the same, timed or not")
    void roundTrips() throws Exception {
        LocalTrackSettings timed = LocalTrackSettings.parse(SAMPLE);
        assertEquals(timed.lines(), LocalTrackSettings.parse(timed.write()).lines());

        LocalTrackSettings mixed = timed.withPermissions(List.of("a.b", "!c.d")).withLines(List.of(
                new LocalTrackSettings.Line(null, "no \"time\""),
                new LocalTrackSettings.Line(83_500L, "later")));
        LocalTrackSettings back = LocalTrackSettings.parse(mixed.write());
        assertEquals(mixed.lines(), back.lines());
        assertEquals(mixed.permissions(), back.permissions());
    }

    @Test
    @DisplayName("metadata reads back, and one line replaces title and author only once it has text")
    void metadata() throws Exception {
        LocalTrackSettings.Meta meta = new LocalTrackSettings.Meta("Rain", "Nobody", "Rain by nobody", true);
        LocalTrackSettings back = LocalTrackSettings.parse(LocalTrackSettings.EMPTY.withMeta(meta).write());
        assertEquals(meta, back.meta());

        assertEquals(new LocalTrackSettings.Shown("Rain by nobody", ""), meta.apply("file", "tag"));
        assertEquals(new LocalTrackSettings.Shown("Rain", "tag"),
                new LocalTrackSettings.Meta("Rain", null, null, true).apply("file", "tag"));
    }

    @Test
    @DisplayName("chat input takes an optional [mm:ss] in front")
    void parsesTyped() {
        assertEquals(new LocalTrackSettings.Line(83_000L, "Строка"), LocalTrackSettings.parseTyped("[01:23] Строка"));
        assertEquals(new LocalTrackSettings.Line(null, "just words"), LocalTrackSettings.parseTyped("just words"));
        assertNull(LocalTrackSettings.parseTyped("   "));
    }

    @Test
    @DisplayName("lines without a timecode fall between their neighbours, or spread evenly when sync is off")
    void placesUntimed() {
        LocalTrackSettings settings = LocalTrackSettings.EMPTY.withLines(List.of(
                new LocalTrackSettings.Line(10_000L, "a"),
                new LocalTrackSettings.Line(null, "b"),
                new LocalTrackSettings.Line(20_000L, "c")));

        SyncedLyrics synced = settings.lyrics(60_000L);
        assertEquals(15_000L, synced.lines().get(1).timeMs());

        SyncedLyrics even = settings.withSyncWithTime(false).lyrics(60_000L);
        assertEquals(List.of(0L, 20_000L, 40_000L),
                even.lines().stream().map(SyncedLyrics.Line::timeMs).toList());

        assertNull(settings.withLyricsEnabled(false).lyrics(60_000L));
        assertFalse(settings.lines().isEmpty());
    }
}
