package dev.valkdz.cdisc.config;

import java.time.LocalDate;
import java.time.Month;
import java.util.Locale;

public enum GuiTheme {

    DARK,

    LEGACY,

    PUMPKIN,

    CANDLE,

    LANTERN;

    public static GuiTheme byKey(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "dark" -> DARK;
            case "legacy", "classic" -> LEGACY;
            case "pumpkin", "halloween" -> PUMPKIN;
            case "candle" -> CANDLE;
            case "lantern" -> LANTERN;
            default -> null;
        };
    }

    public static GuiTheme parse(String raw, GuiTheme fallback) {
        GuiTheme found = byKey(raw);
        return found == null ? fallback : found;
    }

    public static GuiTheme forServer(String raw, LocalDate today) {
        if (raw != null && raw.trim().equalsIgnoreCase("auto")) return seasonal(today);
        return parse(raw, DARK);
    }

    // Vanilla's own Halloween window: the days bats spawn in brighter light.
    public static GuiTheme seasonal(LocalDate today) {
        int day = today.getDayOfMonth();
        boolean halloween = today.getMonth() == Month.OCTOBER ? day >= 20
                : today.getMonth() == Month.NOVEMBER && day <= 3;
        return halloween ? PUMPKIN : DARK;
    }

    public GuiTheme next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
