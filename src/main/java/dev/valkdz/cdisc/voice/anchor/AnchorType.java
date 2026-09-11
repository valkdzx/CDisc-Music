package dev.valkdz.cdisc.voice.anchor;

public enum AnchorType {

    BLOCK_DISPLAY,

    MARKER,

    ARMOR_STAND;

    public static AnchorType parse(String raw, AnchorType fallback) {
        if (raw == null) return fallback;
        for (AnchorType type : values()) {
            if (type.name().equalsIgnoreCase(raw.replace('-', '_'))) return type;
        }
        return fallback;
    }
}
