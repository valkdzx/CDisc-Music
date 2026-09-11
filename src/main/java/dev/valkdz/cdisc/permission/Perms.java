package dev.valkdz.cdisc.permission;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Perms {

    public static final String CREATE = "cdisc.create";
    public static final String CREATE_PLAYLIST = "cdisc.create.playlist";
    public static final String CLEAR = "cdisc.clear";
    public static final String DOWNLOAD = "cdisc.download";
    public static final String DOCTOR = "cdisc.doctor";
    public static final String PLAYER = "cdisc.player";
    public static final String PAIR = "cdisc.pair";
    public static final String PORTABLE = "cdisc.portable";
    public static final String MESSAGES = "cdisc.messages";
    public static final String PRESET = "cdisc.preset";
    public static final String ADMIN = "cdisc.admin";

    public static final String[] ALL = {
            CREATE, CREATE_PLAYLIST, CLEAR, DOWNLOAD, DOCTOR, PLAYER, PAIR, PORTABLE,
            MESSAGES, PRESET, ADMIN
    };

    private Perms() {
    }

    public static boolean has(CommandSender sender, String node) {
        return sender.hasPermission(node) || isAdmin(sender);
    }

    public static boolean isAdmin(CommandSender sender) {
        return sender.hasPermission(ADMIN) || sender.isOp();
    }

    public static boolean isLimited(Player player) {
        return !isAdmin(player);
    }
}
