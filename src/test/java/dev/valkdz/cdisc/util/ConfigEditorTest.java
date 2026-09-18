package dev.valkdz.cdisc.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigEditorTest {

    private static final char SEP = ConfigUpgrade.SEP;

    private String shipped(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void writesAChangeAndKeepsTheShippedComments() throws Exception {
        String sources = shipped("sources.yml");

        String updated = ConfigEditor.apply("sources.yml", sources, sources,
                Map.of("soundcloud" + SEP + "proxy", true, "search" + SEP + "max-results", 7));

        YamlConfiguration read = new YamlConfiguration();
        read.loadFromString(updated);
        assertTrue(read.getBoolean("soundcloud.proxy"));
        assertEquals(7, read.getInt("search.max-results"));
        assertTrue(updated.contains("### SoundCloud"));
    }

    @Test
    void refusesASettingTheTemplateDoesNotShip() throws Exception {
        String sources = shipped("sources.yml");

        assertThrows(IOException.class, () -> ConfigEditor.apply("sources.yml", sources, sources,
                Map.of("soundcloud" + SEP + "invented", "x")));
    }

    @Test
    void permissionRulesAndSecretsAreTypedAsSuch() throws Exception {
        List<ConfigEditor.Field> perms = ConfigEditor.fields("permissions.yml",
                shipped("permissions.yml"), shipped("permissions.yml"));
        assertTrue(perms.stream().anyMatch(f -> f.label().equals("actions.disc.download")
                && f.kind() == ConfigEditor.Kind.RULE));

        List<ConfigEditor.Field> tokens = ConfigEditor.fields("tokens.yml",
                shipped("tokens.yml"), shipped("tokens.yml"));
        assertTrue(tokens.stream().anyMatch(f -> f.label().equals("spotify.client-secret")
                && f.kind() == ConfigEditor.Kind.SECRET));
        assertTrue(tokens.stream().anyMatch(f -> f.label().equals("po-token-backend.url")
                && f.kind() == ConfigEditor.Kind.TEXT));
    }

    @Test
    void parsesAndRejects() {
        assertEquals(12, ConfigEditor.parse(ConfigEditor.Kind.INTEGER, " 12 "));
        assertEquals(List.of("mp3", "ogg"), ConfigEditor.parse(ConfigEditor.Kind.LIST, "mp3, ogg,"));
        assertEquals("op", ConfigEditor.parse(ConfigEditor.Kind.RULE, "op"));
        assertEquals(true, ConfigEditor.parse(ConfigEditor.Kind.RULE, "TRUE"));
        assertEquals(List.of("a.b", "c.d"), ConfigEditor.parse(ConfigEditor.Kind.RULE, "[a.b, c.d]"));

        assertThrows(IllegalArgumentException.class, () -> ConfigEditor.parse(ConfigEditor.Kind.INTEGER, "1.5"));
        assertThrows(IllegalArgumentException.class, () -> ConfigEditor.parse(ConfigEditor.Kind.DECIMAL, "NaN"));
        assertThrows(IllegalArgumentException.class, () -> ConfigEditor.parse(ConfigEditor.Kind.TEXT, "a\nb: c"));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigEditor.parse(ConfigEditor.Kind.TEXT, "x".repeat(ConfigEditor.MAX_LENGTH + 1)));
    }
}
