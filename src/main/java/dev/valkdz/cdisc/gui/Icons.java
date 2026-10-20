package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.config.GuiTheme;
import dev.valkdz.cdisc.config.PlayerPrefs;
import dev.valkdz.cdisc.util.HeadUtils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class Icons {

    private Icons() {}

    public static GuiTheme theme(Player player) {
        return PlayerPrefs.theme(player, Main.getInstance().cdiscConfig().getGuiTheme());
    }

    public static ItemStack head(Player player, GuiHeads head, String legacy) {
        GuiTheme theme = theme(player);
        return HeadUtils.createHead(theme == GuiTheme.LEGACY ? legacy : head.texture(theme));
    }

    // A head whose textures are not uploaded yet shows its legacy item in every theme.
    public static ItemStack pick(Player player, GuiHeads head, ItemStack legacy) {
        GuiTheme theme = theme(player);
        String texture = theme == GuiTheme.LEGACY ? null : head.texture(theme);
        return texture == null || texture.isEmpty() ? legacy : HeadUtils.createHead(texture);
    }

    public static ItemStack pick(Player player, GuiHeads head, Material legacy) {
        return pick(player, head, new ItemStack(legacy));
    }

    public static Material pane(Player player, Material pane) {
        GuiTheme theme = theme(player);
        if (theme == GuiTheme.DARK || theme == GuiTheme.LEGACY) return pane;
        boolean orangeHeads = theme == GuiTheme.CANDLE;
        if (pane == Material.BLACK_STAINED_GLASS_PANE) {
            return orangeHeads ? Material.BLACK_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE;
        }
        if (pane == Material.GRAY_STAINED_GLASS_PANE) {
            return orangeHeads ? Material.ORANGE_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE;
        }
        if (pane == Material.LIGHT_GRAY_STAINED_GLASS_PANE) return Material.WHITE_STAINED_GLASS_PANE;
        return pane;
    }
}
