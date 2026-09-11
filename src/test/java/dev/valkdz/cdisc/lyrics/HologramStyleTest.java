package dev.valkdz.cdisc.lyrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramStyleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HologramStyle sample() {
        return new HologramStyle(
                0x123456, 61, 7,
                "§7§l", 200,
                "§f§o", 245,
                8, 2.3, 260, 3, 4,
                true, true, 11, false, false);
    }

    @Test
    @DisplayName("a style survives being written out and read back")
    void roundTrips() {
        HologramStyle original = sample();
        HologramStyle back = HologramStyle.fromJsonString(original.toJsonString(), other());

        assertEquals(original, back);
    }

    @Test
    @DisplayName("every field is actually carried, not defaulted back into place")
    void carriesEveryField() throws Exception {
        HologramStyle original = sample();

        HologramStyle back = HologramStyle.fromJson(
                MAPPER.readTree(original.toJsonString()), other());

        assertEquals(original.backgroundColor(), back.backgroundColor(), "backgroundColor");
        assertEquals(original.backgroundOpacity(), back.backgroundOpacity(), "backgroundOpacity");
        assertEquals(original.brightness(), back.brightness(), "brightness");
        assertEquals(original.textPrefix(), back.textPrefix(), "textPrefix");
        assertEquals(original.textOpacity(), back.textOpacity(), "textOpacity");
        assertEquals(original.currentPrefix(), back.currentPrefix(), "currentPrefix");
        assertEquals(original.currentOpacity(), back.currentOpacity(), "currentOpacity");
        assertEquals(original.size(), back.size(), "size");
        assertEquals(original.height(), back.height(), 0.0001, "height");
        assertEquals(original.lineWidth(), back.lineWidth(), "lineWidth");
        assertEquals(original.linesBefore(), back.linesBefore(), "linesBefore");
        assertEquals(original.linesAfter(), back.linesAfter(), "linesAfter");
        assertEquals(original.shadow(), back.shadow(), "shadow");
        assertEquals(original.seeThrough(), back.seeThrough(), "seeThrough");
        assertEquals(original.fadeTicks(), back.fadeTicks(), "fadeTicks");
        assertEquals(original.slide(), back.slide(), "slide");
        assertEquals(original.countdown(), back.countdown(), "countdown");
    }

    @Test
    @DisplayName("the written form names every field, so nothing is only in equals")
    void writesEveryField() throws Exception {
        var node = MAPPER.readTree(sample().toJsonString());

        assertEquals(HologramStyle.class.getRecordComponents().length, node.size());
    }

    @Test
    @DisplayName("a field the file is missing comes from the fallback")
    void missingFieldsFallBack() {
        HologramStyle fallback = other();
        HologramStyle read = HologramStyle.fromJsonString(
                "{\"size\":3,\"shadow\":true}", fallback);

        assertEquals(3, read.size(), "the one it had");
        assertEquals(fallback.lineWidth(), read.lineWidth(), "one it did not");
        assertEquals(fallback.textPrefix(), read.textPrefix(), "and another");
    }

    @Test
    @DisplayName("nonsense is refused rather than half-read")
    void refusesNonsense() {
        HologramStyle fallback = other();

        assertSame(fallback, HologramStyle.fromJsonString("not json at all", fallback));
        assertSame(fallback, HologramStyle.fromJsonString("", fallback));
        assertSame(fallback, HologramStyle.fromJsonString(null, fallback));

        assertEquals(fallback, HologramStyle.fromJsonString("[1,2,3]", fallback));
    }

    @Test
    @DisplayName("values out of range are pulled back in, not stored as written")
    void clampsOnRead() {
        HologramStyle read = HologramStyle.fromJsonString(
                "{\"size\":99,\"background-opacity\":9000,\"lines-before\":-4,"
                        + "\"fade-ticks\":500,\"height\":90.0,\"line-width\":2}",
                other());

        assertEquals(10, read.size());
        assertEquals(255, read.backgroundOpacity());
        assertEquals(0, read.linesBefore());
        assertEquals(20, read.fadeTicks());
        assertEquals(5.0, read.height(), 0.0001);
        assertEquals(40, read.lineWidth());
    }

    @Test
    @DisplayName("a brightness below zero means the world's own light, whatever the number")
    void brightnessBelowZeroIsOneValue() {
        assertEquals(HologramStyle.BRIGHTNESS_WORLD,
                HologramStyle.fromJsonString("{\"brightness\":-9}", other()).brightness());
        assertEquals(15, HologramStyle.fromJsonString("{\"brightness\":40}", other()).brightness());
    }

    @Test
    @DisplayName("an edit changes one field and leaves the rest alone")
    void editsAreNarrow() {
        HologramStyle before = sample();
        HologramStyle after = before.withSize(2);

        assertEquals(2, after.size());
        assertNotEquals(before, after);
        assertEquals(before, after.withSize(before.size()));
    }

    @Test
    @DisplayName("opacity is folded into the colours the renderer is handed")
    void opacityReachesTheRenderer() {
        HologramStyle solid = sample().withCurrentOpacity(255).withTextOpacity(255);
        HologramStyle faint = solid.withTextOpacity(60);

        assertNotEquals(solid.lyricsStyle().other(), faint.lyricsStyle().other(),
                "a fainter setting has to reach the prefix");
        assertEquals(solid.lyricsStyle().current(), faint.lyricsStyle().current(),
                "and must not touch the other line");
    }

    @Test
    @DisplayName("the written form is readable rather than positional")
    void writesNamedFields() {
        String json = sample().toJsonString();
        assertTrue(json.contains("\"line-width\""), json);
        assertTrue(json.contains("\"current-prefix\""), json);
    }

    private static HologramStyle other() {
        return new HologramStyle(
                0xFEDCBA, 12, HologramStyle.BRIGHTNESS_WORLD,
                "§8", 33,
                "§e", 44,
                1, 0.4, 55, 0, 0,
                false, false, 1, true, true);
    }
}
