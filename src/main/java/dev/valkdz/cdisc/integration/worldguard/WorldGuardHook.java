package dev.valkdz.cdisc.integration.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.internal.WGMetadata;
import com.sk89q.worldguard.bukkit.listener.RegionProtectionListener;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

final class WorldGuardHook {

    static final String FLAG_NAME = "cdisc-jukebox";

    private static final String LAST_MESSAGE_KEY = "worldguard.region.lastMessage";
    private static final long MESSAGE_INTERVAL_MS = 500;

    private static volatile StateFlag flag;
    private static volatile Method denySender;

    private WorldGuardHook() {
    }

    // WorldGuard locks its flag registry when it enables, so this only works from onLoad.
    static void registerFlag(Logger log) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        try {
            StateFlag own = new StateFlag(FLAG_NAME, false);
            registry.register(own);
            flag = own;
        } catch (FlagConflictException | IllegalStateException e) {
            Flag<?> existing = registry.get(FLAG_NAME);
            if (existing instanceof StateFlag state) {
                flag = state;
            } else {
                log.warning("[CDisc] The WorldGuard flag " + FLAG_NAME + " could not be registered ("
                        + e.getMessage() + "); regions still follow membership.");
            }
        }
    }

    static boolean canUse(Player player, Block block) {
        LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player);
        WorldGuard wg = WorldGuard.getInstance();
        if (wg.getPlatform().getSessionManager().hasBypass(local, BukkitAdapter.adapt(block.getWorld()))) {
            return true;
        }

        RegionQuery query = wg.getPlatform().getRegionContainer().createQuery();
        com.sk89q.worldedit.util.Location at = BukkitAdapter.adapt(block.getLocation());
        StateFlag own = flag;
        return own == null ? query.testBuild(at, local) : query.testBuild(at, local, own);
    }

    static boolean toldRecently(Player player) {
        Long last = WGMetadata.getIfPresent(player, LAST_MESSAGE_KEY, Long.class);
        return last != null && System.currentTimeMillis() - last < MESSAGE_INTERVAL_MS;
    }

    // Sharing WorldGuard's own key keeps its refusal and ours from both landing on one click.
    static void markTold(Player player) {
        WGMetadata.put(player, LAST_MESSAGE_KEY, System.currentTimeMillis());
    }

    // Through WorldGuard's own sender, so the region's deny-message flag and plugins that
    // patch that method (WorldGuard-Translator's action bar mode) apply to it too.
    static void sendDenyMessage(Player player, Block block) throws ReflectiveOperationException {
        Method send = denySender;
        if (send == null) {
            send = RegionProtectionListener.class.getDeclaredMethod("formatAndSendDenyMessage",
                    String.class, LocalPlayer.class, String.class);
            send.setAccessible(true);
            denySender = send;
        }

        LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player);
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        String message = query.queryValue(BukkitAdapter.adapt(block.getLocation()), local, Flags.DENY_MESSAGE);
        send.invoke(null, "use that", local, message);
    }
}
