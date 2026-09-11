package dev.valkdz.cdisc.lyrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsRendererTest {

    private static final char S = '§';

    private static SyncedLyrics tenLines() {
        StringBuilder lrc = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            lrc.append(String.format("[%02d:%02d.00]line%d%n", (i * 10) / 60, (i * 10) % 60, i));
        }
        SyncedLyrics parsed = SyncedLyrics.parse(lrc.toString());
        assertNotNull(parsed);
        return parsed;
    }

    private static LyricsRenderer.Options options(int before, int after, long countdownMs) {
        return new LyricsRenderer.Options(before, after,
                LyricsStyle.of(S + "f", S + "8"),
                countdownMs, "●", "○", 1f);
    }

    @Test
    @DisplayName("mid-song, the window is as wide as it was asked to be")
    void windowIsTheRequestedWidth() {

        List<String> lines = LyricsRenderer.window(tenLines(), 50_000, options(2, 3, 0));

        assertEquals(6, lines.size(), "two before, the current one, three after");
        assertTrue(lines.get(0).endsWith("line3"), lines.toString());
        assertTrue(lines.get(2).endsWith("line5"), lines.toString());
        assertTrue(lines.get(5).endsWith("line8"), lines.toString());
    }

    @Test
    @DisplayName("the line being sung is the only one drawn lit")
    void onlyTheCurrentLineIsLit() {
        LyricsStyle style = LyricsStyle.of(S + "f", S + "8");
        List<String> lines = LyricsRenderer.window(tenLines(), 50_000, options(1, 1, 0));

        assertEquals(3, lines.size());

        assertTrue(lines.get(1).startsWith(style.rising(1f)), "current: " + lines.get(1));
        assertTrue(lines.get(2).startsWith(style.other()), "after: " + lines.get(2));
    }

    @Test
    @DisplayName("at the start there is simply less above to show")
    void clampsAtTheStart() {

        List<String> lines = LyricsRenderer.window(tenLines(), 10_000, options(4, 1, 0));

        assertEquals(2, lines.size(), "the first line and one after it");
        assertTrue(lines.get(0).endsWith("line1"), lines.toString());
    }

    @Test
    @DisplayName("at the end there is simply less below")
    void clampsAtTheEnd() {
        List<String> lines = LyricsRenderer.window(tenLines(), 999_000, options(1, 4, 0));

        assertEquals(2, lines.size(), "the last line and one before it");
        assertTrue(lines.get(1).endsWith("line10"), lines.toString());
    }

    @Test
    @DisplayName("through the intro nothing is lit yet")
    void nothingIsLitBeforeTheFirstLine() {
        LyricsStyle style = LyricsStyle.of(S + "f", S + "8");
        List<String> lines = LyricsRenderer.window(tenLines(), 0, options(2, 2, 0));

        assertTrue(lines.size() > 0);
        for (String line : lines) {
            assertTrue(line.startsWith(style.other()),
                    "nothing should be highlighted before the singing starts: " + line);
        }
    }

    @Test
    @DisplayName("the countdown takes a line rather than adding one")
    void countdownKeepsTheBlockTheSameHeight() {
        SyncedLyrics lyrics = tenLines();

        List<String> counting = LyricsRenderer.window(lyrics, 8_000, options(2, 3, 3_000));

        List<String> quiet = LyricsRenderer.window(lyrics, 8_000, options(2, 3, 0));

        assertEquals(quiet.size(), counting.size(),
                "the block must not change height when the dots appear");
        assertTrue(counting.get(0).contains("●"), "first row should be the dots: " + counting.get(0));
        assertTrue(quiet.get(0).endsWith("line1"), quiet.toString());
    }

    @Test
    @DisplayName("the countdown fills a dot a second and stops at the first line")
    void countdownSteps() {
        SyncedLyrics lyrics = tenLines();

        assertEquals(0, LyricsRenderer.countdownStep(lyrics, 0, 3_000),
                "too early for it to have started");
        assertEquals(1, LyricsRenderer.countdownStep(lyrics, 7_100, 3_000));
        assertEquals(2, LyricsRenderer.countdownStep(lyrics, 8_100, 3_000));
        assertEquals(3, LyricsRenderer.countdownStep(lyrics, 9_100, 3_000));
        assertEquals(0, LyricsRenderer.countdownStep(lyrics, 10_000, 3_000),
                "the line has arrived; the dots are done");
        assertEquals(0, LyricsRenderer.countdownStep(lyrics, 7_100, 0),
                "switched off");
    }

    @Test
    @DisplayName("a blank line is drawn as the break marker, not as nothing")
    void blankLinesBecomeAMarker() {
        SyncedLyrics lyrics = SyncedLyrics.parse("[00:10.00]words\n[00:20.00]\n[00:30.00]more");
        assertNotNull(lyrics);

        List<String> lines = LyricsRenderer.window(lyrics, 20_000, options(1, 1, 0));
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).endsWith(LyricsRenderer.BREAK_MARKER), lines.toString());
    }

    @Test
    @DisplayName("asking for no lines around it still shows the one being sung")
    void zeroWidthStillShowsTheCurrentLine() {
        List<String> lines = LyricsRenderer.window(tenLines(), 50_000, options(0, 0, 0));

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith("line5"), lines.toString());
    }

    @Test
    @DisplayName("nothing to show comes back empty rather than as a blank hologram")
    void emptyInputIsEmptyOutput() {
        assertTrue(LyricsRenderer.window(null, 1_000, options(2, 2, 0)).isEmpty());
    }
}
