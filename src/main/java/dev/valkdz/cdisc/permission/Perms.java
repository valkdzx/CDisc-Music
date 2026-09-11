package dev.valkdz.cdisc.permission;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Perms {

    public static final String ADMIN = "cdisc.admin";

    private Perms() {
    }

    public static boolean isAdmin(CommandSender sender) {
        return sender.hasPermission(ADMIN) || sender.isOp();
    }

    public static boolean isLimited(Player player) {
        return !isAdmin(player);
    }
}
