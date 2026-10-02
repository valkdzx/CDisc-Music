package dev.valkdz.cdisc.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OneLineTrackTest {

    @Test
    @DisplayName("a missing author takes its dash with it, before or after the title")
    void dropsAuthorAndDash() {
        assertEquals("Now playing: Rain", MessageManager.dropAuthor("Now playing: \u0000 - Rain"));
        assertEquals("§7§fRain", MessageManager.dropAuthor("§7\u0000 §8- §fRain"));
        assertEquals("Up next: Rain", MessageManager.dropAuthor("Up next: \u0000 — Rain"));
        assertEquals("Track loaded: Rain [3:00]", MessageManager.dropAuthor("Track loaded: Rain - \u0000 [3:00]"));
    }
}
