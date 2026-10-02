package dev.valkdz.cdisc.audio.sabr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class YoutubeCipherTest {

    @Test
    void readsTheEscapedPathOfTheIframeApi() {
        String iframeApi = "var scriptUrl = 'https:\\/\\/www.youtube.com\\/s\\/player\\/8ab5c328"
                + "\\/www-widgetapi.vflset\\/www-widgetapi.js';";
        assertEquals("8ab5c328", YoutubeCipher.playerIdIn(iframeApi));
    }

    @Test
    void readsThePlainPathOfTheEmbedPage() {
        String embed = "\"jsUrl\":\"/s/player/1f2e3d4c/player_ias.vflset/en_US/base.js\"";
        assertEquals("1f2e3d4c", YoutubeCipher.playerIdIn(embed));
    }

    @Test
    void findsNothingWithoutAPlayerPath() {
        assertNull(YoutubeCipher.playerIdIn("var YT = {loading: 0};"));
    }
}
