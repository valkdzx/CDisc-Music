package dev.valkdz.cdisc.util;

import java.util.Locale;

public enum SneakMode {

    TOGGLE,

    RELEASE,

    OFF;

    public static SneakMode byKey(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "toggle" -> TOGGLE;
            case "release", "hold" -> RELEASE;
            case "off", "none" -> OFF;
            default -> null;
        };
    }

    public static SneakMode parse(String raw, SneakMode fallback) {
        SneakMode found = byKey(raw);
        return found == null ? fallback : found;
    }

    public SneakMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
