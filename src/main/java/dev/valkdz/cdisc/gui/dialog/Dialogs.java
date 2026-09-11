package dev.valkdz.cdisc.gui.dialog;

import dev.valkdz.cdisc.Main;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class Dialogs {

    private static final boolean SUPPORTED = probe();

    private static volatile boolean broken;

    private Dialogs() {
    }

    private static boolean probe() {
        try {

            Class.forName("io.papermc.paper.dialog.Dialog");
            Class.forName("net.kyori.adventure.audience.Audience")
                    .getMethod("showDialog", Class.forName("net.kyori.adventure.dialog.DialogLike"));
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    public static boolean supported() {
        return SUPPORTED && !broken;
    }

    public static boolean playerScreenWanted(Main plugin) {
        return supported() && plugin.cdiscConfig().isPlayerDialogEnabled();
    }

    public static boolean playerScreenWanted(Main plugin, Player player) {
        if (!playerScreenWanted(plugin)) return false;
        return plugin.getScreenPreferences().wantsDialog(player.getUniqueId(), true);
    }

    public static boolean switchable(Main plugin) {
        return playerScreenWanted(plugin);
    }

    public static void choose(Main plugin, Player player, boolean dialog) {
        plugin.getScreenPreferences().set(player.getUniqueId(), dialog);
    }

    public static boolean openPlayer(Main plugin, Player player, Block block) {
        try {
            PlayerDialog.open(plugin, player, block);
            return true;
        } catch (LinkageError | RuntimeException e) {

            broken = true;
            plugin.getLogger().warning("This server has the dialog API but could not"
                    + " show one (" + e + "). Falling back to the inventory screen"
                    + " for the rest of this run; set player-dialog to false in"
                    + " config.yml to stop trying at startup.");
            return false;
        }
    }
}
