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

    public static boolean legacy(Player player) {
        return theme(player) == GuiTheme.LEGACY;
    }

    public static ItemStack head(Player player, String dark, String legacy) {
        return HeadUtils.createHead(legacy(player) ? legacy : dark);
    }

    public static ItemStack pick(Player player, String dark, ItemStack legacy) {
        return legacy(player) ? legacy : HeadUtils.createHead(dark);
    }

    public static ItemStack pick(Player player, String dark, Material legacy) {
        return legacy(player) ? new ItemStack(legacy) : HeadUtils.createHead(dark);
    }
}
