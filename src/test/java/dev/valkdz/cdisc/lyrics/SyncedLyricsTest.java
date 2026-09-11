package dev.valkdz.cdisc.lyrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncedLyricsTest {

    @Test
    @DisplayName("plain timecoded lines parse in order")
    void parsesPlainLines() {
        SyncedLyrics lyrics = SyncedLyrics.parse(
                "[00:01.00]first\n[00:05.50]second\n[00:12.25]third");

        assertNotNull(lyrics);
        assertEquals(3, lyrics.size());
        assertEquals(1000, lyrics.lines().get(0).timeMs());
        assertEquals(5500, lyrics.lines().get(1).timeMs());
        assertEquals(12250, lyrics.lines().get(2).timeMs());
        assertEquals("second", lyrics.lines().get(1).text());
    }

    @Test
    @DisplayName("hundredths, thousandths and no fraction at all are each read right")
    void readsEveryFractionLength() {
        SyncedLyrics lyrics = SyncedLyrics.parse(
                "[00:01]a\n[00:02.5]b\n[00:03.25]c\n[00:04.125]d");

        assertNotNull(lyrics);
        assertEquals(1000, lyrics.lines().get(0).timeMs());
        assertEquals(2500, lyrics.lines().get(1).timeMs(), "one digit is tenths");
        assertEquals(3250, lyrics.lines().get(2).timeMs(), "two are hundredths");
        assertEquals(4125, lyrics.lines().get(3).timeMs(), "three are thousandths");
    }

    @Test
    @DisplayName("one line under several timestamps becomes one entry each")
    void expandsRepeatedTimestamps() {
        SyncedLyrics lyrics = SyncedLyrics.parse("[00:10.00][01:10.00]a chorus");

        assertNotNull(lyrics);
        assertEquals(2, lyrics.size());
        assertEquals("a chorus", lyrics.lines().get(0).text());
        assertEquals("a chorus", lyrics.lines().get(1).text());
        assertEquals(70000, lyrics.lines().get(1).timeMs());
    }

    @Test
    @DisplayName("a bracket inside the words is not mistaken for a timestamp")
    void bracketsInsideTextSurvive() {
        SyncedLyrics lyrics = SyncedLyrics.parse("[00:03.00]not a stamp [00:99.00] here");

        assertNotNull(lyrics);
        assertEquals(1, lyrics.size());
        assertTrue(lyrics.lines().get(0).text().contains("[00:99.00]"));
    }

    @Test
    @DisplayName("metadata is skipped and offset is applied the way the format says")
    void appliesOffset() {

        SyncedLyrics lyrics = SyncedLyrics.parse(
                "[ar:Somebody]\n[ti:A Song]\n[offset:+500]\n[00:10.00]line");

        assertNotNull(lyrics);
        assertEquals(1, lyrics.size());
        assertEquals(9500, lyrics.lines().get(0).timeMs());
    }

    @Test
    @DisplayName("lines out of order are sorted")
    void sortsOutOfOrderLines() {
        SyncedLyrics lyrics = SyncedLyrics.parse("[00:20.00]late\n[00:05.00]early");

        assertNotNull(lyrics);
        assertEquals("early", lyrics.lines().get(0).text());
    }

    @Test
    @DisplayName("a document with nothing timed in it is not lyrics")
    void refusesUntimedDocuments() {
        assertNull(SyncedLyrics.parse("just some words\nand more of them"));
        assertNull(SyncedLyrics.parse("[ar:Somebody]\n[ti:A Song]"));
        assertNull(SyncedLyrics.parse(""));
        assertNull(SyncedLyrics.parse(null));
    }

    @Test
    @DisplayName("a blank line is kept, since it marks an instrumental break")
    void keepsBlankLines() {
        SyncedLyrics lyrics = SyncedLyrics.parse("[00:01.00]words\n[00:08.00]");

        assertNotNull(lyrics);
        assertEquals(2, lyrics.size());
        assertTrue(lyrics.lines().get(1).isBlank());
    }

    @Test
    @DisplayName("the line being sung is the last one already reached")
    void findsTheCurrentLine() {
        SyncedLyrics lyrics = SyncedLyrics.parse(
                "[00:10.00]a\n[00:20.00]b\n[00:30.00]c");
        assertNotNull(lyrics);

        assertEquals(-1, lyrics.indexAt(0), "before the first line, nothing is lit");
        assertEquals(-1, lyrics.indexAt(9999));
        assertEquals(0, lyrics.indexAt(10000), "exactly on a cue counts as reached");
        assertEquals(0, lyrics.indexAt(19999));
        assertEquals(1, lyrics.indexAt(20000));
        assertEquals(2, lyrics.indexAt(999999), "past the end, the last line stays");
    }
}
