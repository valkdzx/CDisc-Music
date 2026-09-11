package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.speaker.SpeakerGroup;
import dev.valkdz.cdisc.speaker.SpeakerGroupManager;
import dev.valkdz.cdisc.speaker.SpeakerSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PairGuiListener implements Listener {

    private static final int MAX_NAME_LENGTH = 24;

    private final Main plugin;

    public PairGuiListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof PairGuiHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;

        e.setCancelled(true);

        int raw = e.getRawSlot();

        if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) return;

        switch (holder.getMode()) {
            case MANAGE -> handleManage(holder, player, raw);
            case PICKER -> handlePicker(holder, player, raw);
            case SETTINGS -> handleSettings(holder, player, raw, e.isRightClick());
        }
    }

    private void handleSettings(PairGuiHolder holder, Player player, int raw,
                                boolean rightClick) {
        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        SpeakerGroup group = groups.groupAt(holder.getAnchor());
        Block subject = holder.getSubject();
        if (subject == null) {
            player.closeInventory();
            return;
        }

        boolean standalone = group == null;
        if (!standalone && !groups.canManage(player, group)) {
            player.closeInventory();
            msg(player, "§c", "speaker.protected", group.name());
            return;
        }

        if (raw == PairGuiManager.SLOT_SETTINGS_BACK) {
            if (standalone) {
                plugin.getPlayerGuiManager().open(player, subject);
            } else {
                plugin.getPairGuiManager().openManage(player, holder.getAnchor());
            }
            return;
        }
        if (raw == PairGuiManager.SLOT_PROMOTE) {
            if (standalone || group.isMain(subject.getLocation())) return;
            promote(player, group, subject);
            return;
        }
        if (raw == PairGuiManager.SLOT_UNLINK) {
            if (standalone || group.isMain(subject.getLocation())) return;
            unlink(player, group, subject);
            return;
        }
        if (raw == PairGuiManager.SLOT_NAME) {
            plugin.getPairGuiManager().promptForTag(player, subject);
            return;
        }

        SpeakerSettings settings = SpeakerSettings.of(subject);
        SpeakerSettings updated = switch (raw) {
            case PairGuiManager.SLOT_CHANNEL -> settings.withChannel(settings.channel().next());
            case PairGuiManager.SLOT_VOLUME ->
                    rightClick ? settings.quieter() : settings.louder();
            case PairGuiManager.SLOT_PARTICLES -> settings.withParticles(!settings.particles());
            default -> null;
        };
        if (updated == null) return;

        SpeakerSettings.store(subject, updated);
        if (standalone) {
            plugin.getAudioPlayerManager().applySpeakerSettings(subject);
        } else {

            plugin.getAudioPlayerManager().applySpeakerSettings(group.main().getBlock(), subject);
        }
        plugin.getPairGuiManager().openSettings(player, holder.getAnchor(), subject);
    }

    private void handleManage(PairGuiHolder holder, Player player, int raw) {
        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        SpeakerGroup group = groups.groupAt(holder.getAnchor());
        if (group == null) {
            player.closeInventory();
            return;
        }
        if (!groups.canManage(player, group)) {
            player.closeInventory();
            msg(player, "§c", "speaker.protected", group.name());
            return;
        }

        if (raw == PairGuiManager.SLOT_MANAGE_BACK) {

            plugin.getQueueGuiManager().open(player, holder.getAnchor());
            return;
        }
        if (raw == PairGuiManager.SLOT_ADD) {
            plugin.getPairGuiManager().openPicker(player, group.main().getBlock());
            return;
        }
        if (raw == PairGuiManager.SLOT_DISSOLVE) {
            dissolve(player, group);
            return;
        }

        Block target = holder.targetAt(raw - PairGuiManager.ROW_START);
        if (target == null) return;

        plugin.getPairGuiManager().openSettings(player, group.main().getBlock(), target);
    }

    private void handlePicker(PairGuiHolder holder, Player player, int raw) {
        if (raw == PairGuiManager.SLOT_BACK) {
            plugin.getPairGuiManager().openManage(player, holder.getAnchor());
            return;
        }

        Block target = holder.targetAt(raw - PairGuiManager.ROW_START);
        if (target == null) return;

        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        SpeakerGroup group = groups.groupAt(holder.getAnchor());
        if (group == null) {
            player.closeInventory();
            return;
        }
        if (!groups.canManage(player, group)) {
            player.closeInventory();
            msg(player, "§c", "speaker.protected", group.name());
            return;
        }

        if (target.getType() != Material.JUKEBOX) {
            msg(player, "§c", "command.pair.no_jukebox");
            plugin.getPairGuiManager().openPicker(player, holder.getAnchor());
            return;
        }
        SpeakerGroupManager.PairResult check = groups.canAdd(group, target);
        if (check != SpeakerGroupManager.PairResult.OK) {
            reject(player, check, target);
            plugin.getPairGuiManager().openPicker(player, holder.getAnchor());
            return;
        }

        groups.addSpeaker(group, target);
        plugin.getAudioPlayerManager().attachSpeakerLive(group.main().getBlock(), target);
        ok(player, "command.pair.added", group.name(), String.valueOf(group.size()));

        plugin.getPairGuiManager().openPicker(player, holder.getAnchor());
    }

    private void promote(Player player, SpeakerGroup group, Block target) {

        plugin.getAudioPlayerManager().stopPlaying(group.main().getBlock(),
                plugin.getAudioPlayerManager().getGeneration(group.main().getBlock()));

        plugin.getSpeakerGroupManager().promote(group, target);
        ok(player, "gui.pair.promoted");
        plugin.getPairGuiManager().openManage(player, target);
    }

    private void unlink(Player player, SpeakerGroup group, Block target) {
        plugin.getAudioPlayerManager().detachSpeakerLive(group.main().getBlock(), target);
        plugin.getSpeakerGroupManager().removeSpeaker(group, target);
        ok(player, "command.pair.removed", group.name());
        plugin.getPairGuiManager().openManage(player, group.main().getBlock());
    }

    private void dissolve(Player player, SpeakerGroup group) {
        Block main = group.main().getBlock();
        for (Location speaker : group.speakers()) {
            plugin.getAudioPlayerManager().detachSpeakerLive(main, speaker.getBlock());
        }
        String name = group.name();
        plugin.getSpeakerGroupManager().dissolve(group);
        player.closeInventory();
        ok(player, "command.pair.dissolved", name);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        Block naming = plugin.getPairGuiManager().getAwaitingName(player);
        Block tagging = plugin.getPairGuiManager().getAwaitingTag(player);
        if (naming == null && tagging == null) return;

        e.setCancelled(true);
        plugin.getPairGuiManager().cancelNaming(player);

        String typed = e.getMessage().trim();
        if (typed.equals(".")) return;

        if (naming != null) {
            // Chat events arrive asynchronously; everything below touches the world.
            Bukkit.getScheduler().runTask(plugin, () -> createNamed(player, naming, typed));
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> renameSpeaker(player, tagging, typed));
        }
    }

    private void renameSpeaker(Player player, Block block, String typed) {
        if (!player.isOnline()) return;

        String name = org.bukkit.ChatColor.stripColor(typed);
        if (name.isEmpty() || name.length() > SpeakerSettings.MAX_TAG_LENGTH) {
            msg(player, "§c", "command.pair.tag_too_long",
                    String.valueOf(SpeakerSettings.MAX_TAG_LENGTH));
            return;
        }
        if (block.getType() != Material.JUKEBOX) {
            msg(player, "§c", "command.pair.no_jukebox");
            return;
        }

        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(block);
        if (group != null && !plugin.getSpeakerGroupManager().canManage(player, group)) {
            msg(player, "§c", "speaker.protected", group.name());
            return;
        }

        SpeakerSettings.store(block, SpeakerSettings.of(block).withTag(name));
        ok(player, "command.pair.tag_set", name);

        if (group != null) {
            plugin.getPairGuiManager().openSettings(player, group.main().getBlock(), block);
        }
    }

    private void createNamed(Player player, Block block, String typed) {
        if (!player.isOnline()) return;

        String name = org.bukkit.ChatColor.stripColor(typed);
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            msg(player, "§c", "gui.pair.name_invalid", String.valueOf(MAX_NAME_LENGTH));
            return;
        }

        SpeakerGroupManager groups = plugin.getSpeakerGroupManager();
        if (groups.byName(name) != null) {
            msg(player, "§c", "command.pair.name_taken", name);
            return;
        }
        if (block.getType() != Material.JUKEBOX) {
            msg(player, "§c", "command.pair.no_jukebox");
            return;
        }
        if (groups.groupAt(block) != null) {
            msg(player, "§c", "command.pair.already_paired");
            return;
        }

        groups.create(name, player.getUniqueId(), block);
        ok(player, "command.pair.created", name);

        plugin.getPairGuiManager().openPicker(player, block);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getPairGuiManager().cancelNaming(e.getPlayer());
    }

    private void reject(Player player, SpeakerGroupManager.PairResult result, Block block) {
        switch (result) {
            case DISABLED -> msg(player, "§c", "command.pair.disabled");
            case ALREADY_PAIRED -> {
                SpeakerGroup taken = plugin.getSpeakerGroupManager().groupAt(block);
                if (taken == null) {
                    msg(player, "§c", "command.pair.already_paired");
                } else {
                    msg(player, "§c", "command.pair.already_paired_by", taken.name());
                }
            }
            case DIFFERENT_WORLD -> msg(player, "§c", "command.pair.different_world");
            case TOO_FAR -> msg(player, "§c", "command.pair.too_far",
                    String.valueOf(plugin.cdiscConfig().getSpeakerMaxDistance()));
            case GROUP_FULL -> msg(player, "§c", "command.pair.group_full",
                    String.valueOf(plugin.cdiscConfig().getSpeakerMaxPerGroup()));
            default -> msg(player, "§c", "command.pair.add_failed");
        }
    }

    private void msg(Player player, String colour, String key, String... args) {
        player.sendMessage(colour + plugin.getMessageManager().get(player, key, (Object[]) args));
    }

    private void ok(Player player, String key, String... args) {
        dev.valkdz.cdisc.util.Chat.actionBar(player,
                "§a" + plugin.getMessageManager().get(player, key, (Object[]) args));
    }
}
