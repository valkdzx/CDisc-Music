package dev.valkdz.cdisc.lyrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsModeTest {

    @Test
    @DisplayName("the stored codes for off and plain words never move")
    void oldValuesKeepTheirMeaning() {
        assertEquals(LyricsMode.OFF, LyricsMode.ofCode((byte) 0));
        assertEquals(LyricsMode.LYRICS, LyricsMode.ofCode((byte) 1));
    }

    @Test
    @DisplayName("every mode round-trips through its stored code and its key")
    void codesAndKeysRoundTrip() {
        Set<Byte> codes = new HashSet<>();
        for (LyricsMode mode : LyricsMode.values()) {
            assertTrue(codes.add(mode.code()), "codes must be distinct");
            assertEquals(mode, LyricsMode.ofCode(mode.code()));
            assertEquals(mode, LyricsMode.ofKey(mode.key()));
        }
        assertNull(LyricsMode.ofKey("nonsense"));
    }

    @Test
    @DisplayName("cycling walks every mode and comes back to off")
    void cycleVisitsEveryMode() {
        LyricsMode mode = LyricsMode.OFF;
        Set<LyricsMode> seen = new HashSet<>();
        do {
            seen.add(mode);
            mode = mode.next();
        } while (mode != LyricsMode.OFF);

        assertEquals(LyricsMode.values().length, seen.size());
    }

    @Test
    @DisplayName("the track line reads the way each mode promises")
    void headerText() {
        long pos = 65_000L;
        long len = 204_000L;

        assertEquals("Song - Band [01:05 / 03:24]",
                LyricsMode.TRACK_LYRICS.header("Song", "Band", pos, len));
        assertEquals("Song - Band [01:05 / 03:24]",
                LyricsMode.TRACK.header("Song", "Band", pos, len));
        assertEquals("[01:05 / 03:24]", LyricsMode.TIME.header("Song", "Band", pos, len));
        assertEquals("[01:05 / 03:24]", LyricsMode.TIME_LYRICS.header("Song", "Band", pos, len));
        assertTrue(LyricsMode.TIME_LYRICS.showsLyrics());
        assertEquals("Song [01:05 / 03:24]", LyricsMode.TRACK.header("Song", " ", pos, len));

        assertNull(LyricsMode.LYRICS.header("Song", "Band", pos, len));
        assertNull(LyricsMode.OFF.header("Song", "Band", pos, len));
    }
}
