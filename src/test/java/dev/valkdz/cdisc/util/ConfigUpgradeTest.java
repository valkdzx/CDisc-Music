package dev.valkdz.cdisc.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUpgradeTest {

    private static YamlConfiguration read(String text) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(text);
        } catch (Exception e) {
            throw new AssertionError("merged file is not valid YAML: " + e.getMessage(), e);
        }
        return cfg;
    }

    private static String resource(String name) {
        try (InputStream in = ConfigUpgradeTest.class.getResourceAsStream("/" + name)) {
            if (in == null) throw new AssertionError(name + " is not on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Nested
    @DisplayName("What the admin changed")
    class Values {

        @Test
        @DisplayName("a changed setting survives the upgrade")
        void keepsChangedValue() {
            String bundled = "volume: 80\nlanguage: auto\n";
            String mine = "volume: 35\nlanguage: auto\n";

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(bundled, mine);

            assertTrue(out.trustworthy);
            assertEquals(35, read(out.text).getInt("volume"));
            assertEquals(1, out.kept);
        }

        @Test
        @DisplayName("a setting left alone is written exactly as the jar ships it")
        void untouchedFileConvergesOnTheTemplate() {
            String bundled = "# a note\nvolume: 80\n";

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(bundled, "volume: 80\n");

            assertEquals(bundled, out.text);
        }

        @Test
        @DisplayName("a list the admin rewrote survives")
        void keepsChangedList() {
            String bundled = "clients:\n  - android-vr\n  - web\n";
            String mine = "clients:\n  - tv\n";

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(bundled, mine);

            assertTrue(out.trustworthy);
            assertEquals(List.of("tv"), read(out.text).getStringList("clients"));
        }

        @Test
        @DisplayName("a subtree under an empty map is carried across whole")
        void keepsOpenEndedMap() {
            String bundled = "search:\n  per-source: {}\n";
            String mine = "search:\n  per-source:\n    spotify: 3\n";

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(bundled, mine);

            assertTrue(out.trustworthy);
            assertEquals(3, read(out.text).getInt("search.per-source.spotify"));
        }

        @Test
        @DisplayName("a value with a colon in it is not mistaken for a key")
        void keepsUrlValues() {
            String bundled = "url: 'https://example.invalid/a'\n";
            String mine = "url: 'https://mine.invalid/b?x=1'\n";

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(bundled, mine);

            assertTrue(out.trustworthy);
            assertEquals("https://mine.invalid/b?x=1", read(out.text).getString("url"));
        }

        @Test
        @DisplayName("an emptied credential stays empty rather than reverting to the default")
        void keepsClearedValue() {
            String bundled = "po-token-backend:\n  url: 'https://public.invalid'\n";
            String mine = "po-token-backend:\n  url: ''\n";

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(bundled, mine);

            assertTrue(out.trustworthy);
            assertEquals("", read(out.text).getString("po-token-backend.url"));
        }
    }

    @Nested
    @DisplayName("What the new version brings")
    class Structure {

        @Test
        @DisplayName("a setting added in the new version arrives with its default")
        void addsNewKey() {
            String bundled = "volume: 80\nnew-thing: true\n";

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(bundled, "volume: 35\n");

            assertTrue(out.trustworthy);
            assertTrue(read(out.text).getBoolean("new-thing"));
            assertEquals(35, read(out.text).getInt("volume"));
            assertEquals(List.of("new-thing"), out.added);
        }

        @Test
        @DisplayName("a whole new section arrives, and the old values around it stay")
        void addsNewSection() {
            String bundled = "volume: 80\nlyrics:\n  enabled: true\n  height: 1.6\n";

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(bundled, "volume: 35\n");

            assertTrue(out.trustworthy);
            assertTrue(read(out.text).getBoolean("lyrics.enabled"));
            assertEquals(35, read(out.text).getInt("volume"));
        }

        @Test
        @DisplayName("rewritten comments replace the old ones")
        void refreshesComments() {
            String bundled = "# Read the token out of the player request.\nvolume: 80\n";
            String mine = "# Read the token off the watch page.\nvolume: 35\n";

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(bundled, mine);

            assertTrue(out.text.contains("out of the player request"));
            assertFalse(out.text.contains("off the watch page"));
            assertEquals(35, read(out.text).getInt("volume"));
        }

        @Test
        @DisplayName("a setting this version no longer reads is dropped and named")
        void dropsObsoleteKey() {
            ConfigUpgrade.Outcome out = ConfigUpgrade.merge("volume: 80\n", "volume: 80\ngone: 1\n");

            assertTrue(out.trustworthy);
            assertFalse(read(out.text).contains("gone"));
            assertEquals(List.of("gone"), out.dropped);
        }

        @Test
        @DisplayName("a permission node keeps its dots instead of becoming three sections")
        void keepsDottedKeys() {
            String bundled = "defaults:\n  cdisc.create: true\n  cdisc.download: op\n";
            String mine = "defaults:\n  cdisc.create: op\n  cdisc.download: op\n";

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(bundled, mine);

            assertTrue(out.trustworthy);
            assertTrue(out.text.contains("cdisc.create: op"));
            assertEquals("op", read(out.text).getString("defaults.cdisc.create"));
        }
    }

    @Nested
    @DisplayName("The files actually shipped")
    class Shipped {

        @Test
        @DisplayName("merging a bundled file with itself changes nothing")
        void idempotentOnItself() {
            assertTrue(ConfigUpgrade.FILES.size() >= 6, "the upgrade list looks short");

            for (String name : ConfigUpgrade.FILES) {
                String text = resource(name);
                ConfigUpgrade.Outcome out = ConfigUpgrade.merge(text, text);
                assertTrue(out.trustworthy, name + " failed its own round trip");
                assertEquals(text, out.text, name + " was rewritten when nothing had changed");
                assertTrue(out.added.isEmpty(), name + " invented keys");
                assertTrue(out.dropped.isEmpty(), name + " dropped keys");
            }
        }

        @Test
        @DisplayName("an admin's edits to a shipped file survive, and a second pass is a no-op")
        void carriesEditsAndSettles() {
            String bundled = resource("config.yml");

            YamlConfiguration edited = read(bundled);
            edited.set("volume", 25);
            edited.set("language", "ru_RU");
            edited.set("lyrics.size", 9);
            edited.set("speaker-group.max-per-group", 3);

            ConfigUpgrade.Outcome first = ConfigUpgrade.merge(bundled, edited.saveToString());
            assertTrue(first.trustworthy);

            YamlConfiguration after = read(first.text);
            assertEquals(25, after.getInt("volume"));
            assertEquals("ru_RU", after.getString("language"));
            assertEquals(9, after.getInt("lyrics.size"));
            assertEquals(3, after.getInt("speaker-group.max-per-group"));

            ConfigUpgrade.Outcome second = ConfigUpgrade.merge(bundled, first.text);
            assertEquals(first.text, second.text, "the upgrade did not settle after one pass");
        }

        @Test
        @DisplayName("an old file missing every recent key comes back whole")
        void fillsInAnOldFile() {
            String bundled = resource("sources.yml");

            ConfigUpgrade.Outcome out = ConfigUpgrade.merge(
                    bundled, "enabled:\n  youtube: false\n");

            assertTrue(out.trustworthy);
            YamlConfiguration after = read(out.text);
            assertFalse(after.getBoolean("enabled.youtube"));
            assertTrue(after.contains("youtube.sabr"));
            assertTrue(after.contains("search.default-results"));
            assertFalse(out.added.isEmpty());
        }
    }
}
