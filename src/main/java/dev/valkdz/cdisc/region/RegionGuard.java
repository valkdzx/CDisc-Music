package dev.valkdz.cdisc.region;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Chat;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class RegionGuard {

    private final Main plugin;
    private final boolean worldGuard;
    private boolean failureLogged;
    private boolean messageFailureLogged;

    public RegionGuard(Main plugin) {
        this.plugin = plugin;
        this.worldGuard = plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard");
        if (worldGuard) {
            plugin.getLogger().info("WorldGuard found: jukeboxes in a region answer only its members "
                    + "(region flag " + WorldGuardHook.FLAG_NAME + ").");
        }
    }

    // WorldGuardHook names WorldGuard classes, so it must not load unless the plugin is there.
    public static void registerFlag(Main plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("WorldGuard") == null) return;
        try {
            WorldGuardHook.registerFlag(plugin.getLogger());
        } catch (Throwable t) {
            plugin.getLogger().warning("[CDisc] Could not register the WorldGuard flag: " + t);
        }
    }

    public boolean allows(Player player, Block block) {
        if (!worldGuard || !plugin.cdiscConfig().isWorldGuardEnabled()) return true;
        if (plugin.getPortableJukeboxManager() != null
                && plugin.getPortableJukeboxManager().isCarried(block)) {
            return true;
        }

        try {
            return WorldGuardHook.canUse(player, block);
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                plugin.getLogger().warning("[CDisc] WorldGuard region check failed, jukeboxes are "
                        + "left unprotected: " + t);
            }
            return true;
        }
    }

    public boolean require(Player player, Block block) {
        if (allows(player, block)) return true;
        refuse(player, block);
        return false;
    }

    private void refuse(Player player, Block block) {
        try {
            if (WorldGuardHook.toldRecently(player)) return;
            WorldGuardHook.markTold(player);
            if (plugin.cdiscConfig().usesWorldGuardDenyMessage()) {
                WorldGuardHook.sendDenyMessage(player, block);
                return;
            }
        } catch (Throwable t) {
            if (!messageFailureLogged) {
                messageFailureLogged = true;
                plugin.getLogger().warning("[CDisc] WorldGuard's deny message could not be sent, "
                        + "using perms.region_denied instead: " + t);
            }
        }
        Chat.deliver(plugin, player, "§c", plugin.getMessageManager().get(player, "perms.region_denied"));
    }
}
