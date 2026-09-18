package dev.valkdz.cdisc.permission;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Perms {

    public static final String ADMIN = "cdisc.admin";

    public static final String CONFIG = "cdisc.admin.config";

    private Perms() {
    }

    public static boolean isAdmin(CommandSender sender) {
        return sender.hasPermission(ADMIN) || sender.hasPermission(CONFIG) || sender.isOp();
    }

    public static boolean isLimited(Player player) {
        return !isAdmin(player);
    }
}
