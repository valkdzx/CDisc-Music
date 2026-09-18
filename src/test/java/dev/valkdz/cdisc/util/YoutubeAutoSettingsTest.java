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

        String updated = YoutubeAutoSettings.replaceInSection(text, "youtube", "proxy");

        assertEquals("other:\n  proxy: false\n\nyoutube:\n  # stays\n  proxy: true\n  sabr: true\n", updated);
    }

    @Test
    void leavesAKeyThatIsNotFalseAlone() {
        assertNull(YoutubeAutoSettings.replaceInSection("youtube:\n  proxy: true\n", "youtube", "proxy"));
        assertNull(YoutubeAutoSettings.replaceInSection("youtube:\n  sabr: false\n", "youtube", "proxy"));
        assertNull(YoutubeAutoSettings.replaceInSection("youtube:\n  proxy: false\n", "soundcloud", "proxy"));
    }

    @Test
    void worksOnTheShippedFile() throws Exception {
        String shipped;
        try (InputStream in = getClass().getResourceAsStream("/" + SourcesConfig.FILE_NAME)) {
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        String updated = YoutubeAutoSettings.replaceInSection(shipped, "youtube", "proxy");
        updated = YoutubeAutoSettings.replaceInSection(updated, "soundcloud", "proxy");

        YamlConfiguration read = new YamlConfiguration();
        read.loadFromString(updated);
        assertTrue(read.getBoolean("youtube.proxy"));
        assertTrue(read.getBoolean("soundcloud.proxy"));
        assertTrue(read.getBoolean("youtube.fallback-api"));
    }
}
