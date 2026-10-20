package dev.valkdz.cdisc.feature.lyrics;

import dev.valkdz.cdisc.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LyricsSourcesTest {

    @Test
    void aStubLeftOnLrclibIsNotTakenForLyrics() throws Exception {
        assertNull(LrcLibProvider.synced(Json.parse("{\"syncedLyrics\":\"[00:00.00]probe\"}")));
        assertNull(LrcLibProvider.synced(Json.parse("{\"instrumental\":true,"
                + "\"syncedLyrics\":\"[00:01.00]a\\n[00:02.00]b\\n[00:03.00]c\"}")));
        assertNotNull(LrcLibProvider.synced(Json.parse(
                "{\"syncedLyrics\":\"[00:01.00]a\\n[00:02.00]b\\n[00:03.00]c\"}")));
    }

    @Test
    void neteaseCreditLinesAreDroppedWhateverTheColon() {
        SyncedLyrics raw = SyncedLyrics.parse("[00:00.00]作词 : Зелинский Андрей\n[00:00.50]作曲：Струй Максим\n"
                + "[00:12.00]Давалке МСК стреляю сиги\n");

        SyncedLyrics clean = NetEaseProvider.withoutCredits(raw);

        assertNotNull(clean);
        assertEquals("Давалке МСК стреляю сиги", clean.lines().get(0).text());
        assertEquals(1, clean.lines().stream().filter(line -> !line.isBlank()).count());
    }
}
