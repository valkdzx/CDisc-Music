package dev.valkdz.cdisc.feature.broadcast;

import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Locale;

public enum MicColor {

    RED(Material.RED_WOOL, "§c", 0xFF5555),
    ORANGE(Material.ORANGE_WOOL, "§6", 0xFFAA00),
    YELLOW(Material.YELLOW_WOOL, "§e", 0xFFFF55),
    LIME(Material.LIME_WOOL, "§a", 0x55FF55),
    AQUA(Material.LIGHT_BLUE_WOOL, "§b", 0x55FFFF),
    PINK(Material.MAGENTA_WOOL, "§d", 0xFF55FF);

    private final Material wool;
    private final String chat;
    private final int rgb;

    MicColor(Material wool, String chat, int rgb) {
        this.wool = wool;
        this.chat = chat;
        this.rgb = rgb;
    }

    public Material wool() {
        return wool;
    }

    public String chat() {
        return chat;
    }

    public Color color() {
        return Color.fromRGB(rgb);
    }

    public String messageKey() {
        return "broadcast.color." + name().toLowerCase(Locale.ROOT);
    }
}
