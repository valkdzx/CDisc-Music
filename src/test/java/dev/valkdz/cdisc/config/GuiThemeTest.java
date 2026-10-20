package dev.valkdz.cdisc.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiThemeTest {

    @Test
    void autoIsPumpkinOnlyInsideVanillasHalloween() {
        assertEquals(GuiTheme.DARK, GuiTheme.forServer("auto", LocalDate.of(2026, 10, 19)));
        assertEquals(GuiTheme.PUMPKIN, GuiTheme.forServer("auto", LocalDate.of(2026, 10, 20)));
        assertEquals(GuiTheme.PUMPKIN, GuiTheme.forServer("auto", LocalDate.of(2026, 10, 31)));
        assertEquals(GuiTheme.PUMPKIN, GuiTheme.forServer(" AUTO ", LocalDate.of(2026, 11, 3)));
        assertEquals(GuiTheme.DARK, GuiTheme.forServer("auto", LocalDate.of(2026, 11, 4)));
        assertEquals(GuiTheme.DARK, GuiTheme.forServer("auto", LocalDate.of(2026, 3, 25)));
    }

    @Test
    void namedThemeIgnoresTheDate() {
        LocalDate halloween = LocalDate.of(2026, 10, 31);
        assertEquals(GuiTheme.DARK, GuiTheme.forServer("dark", halloween));
        assertEquals(GuiTheme.LEGACY, GuiTheme.forServer("classic", halloween));
        assertEquals(GuiTheme.PUMPKIN, GuiTheme.forServer("halloween", LocalDate.of(2026, 5, 1)));
        assertEquals(GuiTheme.CANDLE, GuiTheme.forServer("Candle", LocalDate.of(2026, 5, 1)));
        assertEquals(GuiTheme.LANTERN, GuiTheme.forServer("lantern", halloween));
        assertEquals(GuiTheme.DARK, GuiTheme.forServer("ghost", halloween));
        assertEquals(GuiTheme.DARK, GuiTheme.forServer(null, halloween));
    }

    @Test
    void everyThemeReadsBackFromItsOwnKey() {
        for (GuiTheme theme : GuiTheme.values()) {
            assertEquals(theme, GuiTheme.byKey(theme.key()));
        }
    }

    @Test
    void switchingWalksEveryThemeAndComesBack() {
        GuiTheme theme = GuiTheme.DARK;
        for (int i = 0; i < GuiTheme.values().length; i++) theme = theme.next();
        assertEquals(GuiTheme.DARK, theme);
        assertEquals(GuiTheme.LEGACY, GuiTheme.DARK.next());
        assertEquals(GuiTheme.DARK, GuiTheme.LANTERN.next());
    }
}
