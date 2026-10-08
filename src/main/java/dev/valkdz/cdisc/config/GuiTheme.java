package dev.valkdz.cdisc.config;

import java.util.Locale;

public enum GuiTheme {

    DARK,

    LEGACY;

    public static GuiTheme byKey(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "dark" -> DARK;
            case "legacy", "classic" -> LEGACY;
            default -> null;
        };
    }

    public static GuiTheme parse(String raw, GuiTheme fallback) {
        GuiTheme found = byKey(raw);
        return found == null ? fallback : found;
    }

    public GuiTheme next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
