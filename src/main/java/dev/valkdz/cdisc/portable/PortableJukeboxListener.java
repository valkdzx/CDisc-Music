package dev.valkdz.cdisc.portable;

import dev.valkdz.cdisc.Main;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class PortableJukeboxListener implements Listener {

    private final Main plugin;

    public PortableJukeboxListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRightClickAir(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR) return;

        if (e.getHand() != EquipmentSlot.HAND) return;

        String id = PortableJukeboxManager.handleIdOf(e.getItem());
        if (id == null) return;

        PortableJukeboxManager.Carry carry =
                plugin.getPortableJukeboxManager().carryById(id);
        if (carry == null) return;

        e.setCancelled(true);
        plugin.getPlayerGuiManager().open(e.getPlayer(), carry.origin());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlock().getType() != Material.JUKEBOX) return;

        String id = PortableJukeboxManager.handleIdOf(e.getItemInHand());
        if (id == null) return;

        PortableJukeboxManager portable = plugin.getPortableJukeboxManager();
        PortableJukeboxManager.Carry carry = portable.carryById(id);
        if (carry == null) {

            return;
        }
        portable.placeBack(carry, e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack stack = e.getItemDrop().getItemStack();
        String id = PortableJukeboxManager.handleIdOf(stack);
        if (id == null) return;

        PortableJukeboxManager portable = plugin.getPortableJukeboxManager();
        PortableJukeboxManager.Carry carry = portable.carryById(id);
        if (carry == null) return;

        portable.endCarry(carry, stack);

        e.getItemDrop().setItemStack(stack);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        endFor(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {

        endFor(e.getEntity());
    }

    private void endFor(org.bukkit.entity.Player player) {
        PortableJukeboxManager portable = plugin.getPortableJukeboxManager();
        for (PortableJukeboxManager.Carry carry : portable.carriesOf(player)) {
            portable.endCarry(carry, portable.findHandle(player, carry));
        }
    }
}
