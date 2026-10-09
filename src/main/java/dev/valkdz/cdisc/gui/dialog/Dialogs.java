package dev.valkdz.cdisc.gui.dialog;

import dev.valkdz.cdisc.Main;
import org.bukkit.entity.Player;

public final class Dialogs {

    private static final boolean SUPPORTED = probe();

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
        return SUPPORTED;
    }

    public static void openConfig(Main plugin, Player player) {
        try {
            ConfigDialog.open(plugin, player);
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Could not show the settings window (" + e + ").");
        }
    }
}
