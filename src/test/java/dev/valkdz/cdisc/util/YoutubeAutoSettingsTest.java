package dev.valkdz.cdisc.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeAutoSettingsTest {

    @Test
    void switchesOnlyTheYoutubeKeyAndKeepsComments() {
        String text = "other:\n  proxy: false\n\nyoutube:\n  # stays\n  proxy: false\n  sabr: true\n";

        String updated = YoutubeAutoSettings.replaceInYoutubeSection(text, "proxy");

        assertEquals("other:\n  proxy: false\n\nyoutube:\n  # stays\n  proxy: true\n  sabr: true\n", updated);
    }

    @Test
    void leavesAKeyThatIsNotFalseAlone() {
        assertNull(YoutubeAutoSettings.replaceInYoutubeSection("youtube:\n  proxy: true\n", "proxy"));
        assertNull(YoutubeAutoSettings.replaceInYoutubeSection("youtube:\n  sabr: false\n", "proxy"));
    }

    @Test
    void worksOnTheShippedFile() throws Exception {
        String shipped;
        try (InputStream in = getClass().getResourceAsStream("/" + SourcesConfig.FILE_NAME)) {
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        String updated = YoutubeAutoSettings.replaceInYoutubeSection(shipped, "proxy");

        YamlConfiguration read = new YamlConfiguration();
        read.loadFromString(updated);
        assertTrue(read.getBoolean("youtube.proxy"));
        assertTrue(read.getBoolean("youtube.fallback-api"));
    }
}
