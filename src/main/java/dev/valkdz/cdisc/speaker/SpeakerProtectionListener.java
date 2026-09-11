package dev.valkdz.cdisc.speaker;

import dev.valkdz.cdisc.Main;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;

import java.util.List;

public final class SpeakerProtectionListener implements Listener {

    private final Main plugin;

    public SpeakerProtectionListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;
        if (!plugin.getSpeakerGroupManager().isSpeaker(block)) return;

        e.setCancelled(true);

        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(block);
        Player player = e.getPlayer();
        player.sendMessage("§c" + plugin.getMessageManager()
                .get(player, "speaker.is_speaker", group == null ? "?" : group.name()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (block.getType() != Material.JUKEBOX) return;

        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        SpeakerGroup group = groups.groupAt(block);
        if (group == null) return;

        Player player = e.getPlayer();
        if (!groups.canManage(player, group)) {
            e.setCancelled(true);
            player.sendMessage("§c" + plugin.getMessageManager()
                    .get(player, "speaker.protected", group.name()));
            return;
        }

        boolean wasMain = group.isMain(block.getLocation());
        String name = group.name();
        releaseMember(group, block);

        player.sendMessage("§e" + plugin.getMessageManager().get(player,
                wasMain ? "speaker.main_broken" : "speaker.speaker_broken", name));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        releaseExploded(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        releaseExploded(e.blockList());
    }

    private void releaseExploded(List<Block> blocks) {
        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();

        for (Block block : List.copyOf(blocks)) {
            if (block.getType() != Material.JUKEBOX) continue;
            SpeakerGroup group = groups.groupAt(block);
            if (group == null) continue;
            releaseMember(group, block);
        }
    }

    private void releaseMember(SpeakerGroup group, Block block) {
        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        Block main = group.main().getBlock();

        if (group.isMain(block.getLocation())) {

            for (org.bukkit.Location speaker : group.speakers()) {
                plugin.getAudioPlayerManager().detachSpeakerLive(main, speaker.getBlock());
            }
        } else {
            plugin.getAudioPlayerManager().detachSpeakerLive(main, block);
        }

        groups.onMemberRemoved(block);
    }
}
