package dev.valkdz.cdisc.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalMusicNamesTest {

    @Test
    @DisplayName("a Cyrillic name keeps its letters when read back from the path")
    void cyrillicNameSurvives(@TempDir Path root) throws Exception {
        Path file = Files.createDirectories(root.resolve("Fashion")).resolve("Вариант3.mp3");
        Files.createFile(file);

        Path real = root.toRealPath();
        Path walked = real.resolve("Fashion").resolve("Вариант3.mp3");
        assertEquals("Fashion/Вариант3.mp3",
                LocalMusicLibrary.realName(real.toUri(), walked, "garbled"));
    }

    @Test
    @DisplayName("typed names match regardless of case, Unicode form or ё")
    void keysFold() {
        String decomposed = Normalizer.normalize("Мой Ёжик.mp3", Normalizer.Form.NFD);
        assertEquals(LocalMusicLibrary.key("мой ежик.MP3"), LocalMusicLibrary.key(decomposed));
    }
}
