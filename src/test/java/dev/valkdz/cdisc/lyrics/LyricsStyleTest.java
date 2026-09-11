package dev.valkdz.cdisc.lyrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsStyleTest {

    private static final char S = '§';

    @Test
    @DisplayName("a legacy colour is read back out of a prefix")
    void readsLegacyColour() {
        assertEquals('f', LyricsStyle.colorCode(S + "f"));
        assertEquals('7', LyricsStyle.colorCode(S + "7" + S + "l"));
        assertEquals('c', LyricsStyle.colorCode(S + "l" + S + "c"));
    }

    @Test
    @DisplayName("a prefix with no colour, or a hex one, reports none")
    void reportsNoColourWhereThereIsNone() {
        assertEquals(0, LyricsStyle.colorCode(""));
        assertEquals(0, LyricsStyle.colorCode(null));
        assertEquals(0, LyricsStyle.colorCode(S + "l"));

        assertEquals(0, LyricsStyle.colorCode(S + "x" + S + "f" + S + "f" + S + "0"
                + S + "0" + S + "0" + S + "0"));
    }

    @Test
    @DisplayName("changing the colour keeps the formatting")
    void changingColourKeepsFormatting() {
        String prefix = S + "7" + S + "l" + S + "o";
        String recoloured = LyricsStyle.withColor(prefix, 'c');

        assertEquals('c', LyricsStyle.colorCode(recoloured));
        assertTrue(LyricsStyle.hasFormat(recoloured, 'l'));
        assertTrue(LyricsStyle.hasFormat(recoloured, 'o'));
    }

    @Test
    @DisplayName("a format toggles on and back off, leaving the colour alone")
    void togglesFormat() {
        String plain = S + "a";

        String bold = LyricsStyle.toggleFormat(plain, 'l');
        assertTrue(LyricsStyle.hasFormat(bold, 'l'));
        assertEquals('a', LyricsStyle.colorCode(bold));

        String back = LyricsStyle.toggleFormat(bold, 'l');
        assertFalse(LyricsStyle.hasFormat(back, 'l'));
        assertEquals('a', LyricsStyle.colorCode(back));
    }

    @Test
    @DisplayName("toggling one format leaves the others where they were")
    void togglingOneFormatLeavesTheRest() {
        String both = LyricsStyle.toggleFormat(
                LyricsStyle.toggleFormat(S + "e", 'l'), 'o');

        String withoutBold = LyricsStyle.toggleFormat(both, 'l');
        assertFalse(LyricsStyle.hasFormat(withoutBold, 'l'));
        assertTrue(LyricsStyle.hasFormat(withoutBold, 'o'));
    }

    @Test
    @DisplayName("a code that is not a format is refused rather than stored")
    void refusesNonFormats() {
        String prefix = S + "a";

        assertEquals(prefix, LyricsStyle.toggleFormat(prefix, 'c'));
    }

    @Test
    @DisplayName("full opacity leaves a prefix untouched")
    void fullOpacityIsANoOp() {
        String prefix = S + "f" + S + "l";
        assertEquals(prefix, LyricsStyle.withOpacity(prefix, 255));
    }

    @Test
    @DisplayName("lowering the opacity darkens the colour and keeps the formatting")
    void opacityDarkens() {
        String faded = LyricsStyle.withOpacity(S + "f" + S + "l", 128);

        assertNotEquals(S + "f" + S + "l", faded);
        assertTrue(LyricsStyle.hasFormat(faded, 'l'));

        assertTrue(faded.startsWith(S + "x"), "expected a hex colour, got: " + faded);
    }

    @Test
    @DisplayName("zero opacity lands on the backdrop itself")
    void zeroOpacityIsBlack() {
        String gone = LyricsStyle.withOpacity(S + "f", 0);
        assertEquals(hex(0x000000), gone);
    }

    @Test
    @DisplayName("a prefix naming no colour has nothing to fade")
    void nothingToFade() {
        assertEquals(S + "l", LyricsStyle.withOpacity(S + "l", 40));
    }

    @Test
    @DisplayName("the fade between two colours ends exactly on each of them")
    void fadeReachesBothEnds() {
        LyricsStyle style = LyricsStyle.of(S + "f", S + "8");
        assertTrue(style.canBlend());

        assertEquals(hex(0x555555), style.rising(0f), "dim end");
        assertEquals(hex(0xFFFFFF), style.rising(1f), "lit end");

        assertEquals(hex(0xFFFFFF), style.falling(0f), "falling starts lit");
        assertEquals(hex(0x555555), style.falling(1f), "falling ends dim");
    }

    @Test
    @DisplayName("halfway through, the fade is neither end")
    void fadeMiddleIsBetween() {
        LyricsStyle style = LyricsStyle.of(S + "f", S + "0");
        String middle = style.rising(0.5f);

        assertNotEquals(hex(0x000000), middle);
        assertNotEquals(hex(0xFFFFFF), middle);
    }

    @Test
    @DisplayName("a pair with no colour on one side cannot be blended")
    void cannotBlendWithoutColours() {
        assertFalse(LyricsStyle.of(S + "f", S + "l").canBlend());
        assertFalse(LyricsStyle.of("", "").canBlend());

        LyricsStyle style = LyricsStyle.of(S + "f", S + "l");
        assertEquals(S + "l", style.rising(0f));
        assertEquals(S + "f", style.rising(1f));
    }

    private static String hex(int rgb) {
        StringBuilder out = new StringBuilder().append(S).append('x');
        String digits = String.format("%06x", rgb);
        for (int i = 0; i < 6; i++) out.append(S).append(digits.charAt(i));
        return out.toString();
    }
}
