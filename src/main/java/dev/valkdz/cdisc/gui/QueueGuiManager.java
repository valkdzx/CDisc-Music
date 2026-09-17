package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.audio.queue.DiscQueue;
import dev.valkdz.cdisc.audio.queue.PlayedPolicy;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.speaker.SpeakerGroup;
import dev.valkdz.cdisc.util.HeadUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class QueueGuiManager {

    public static final int SIZE = 54;

    public static final int SLOT_EXIT = 9;
    public static final int SLOT_NEXT = 18;
    public static final int SLOT_CURRENT = 27;
    public static final int SLOT_POLICY = 36;
    public static final int SLOT_PAIR = 45;

    public static final int CONFIRM_SIZE = 9;
    public static final int CONFIRM_SLOT_YES = 2;
    public static final int CONFIRM_SLOT_DISC = 4;
    public static final int CONFIRM_SLOT_NO = 6;

    private static final Set<Integer> FILLER_SLOTS = Set.of(0, 1, 10, 19, 28, 37, 46);

    public static final int[] QUEUE_SLOTS = buildQueueSlots();

    private static int[] buildQueueSlots() {
        int[] out = new int[DiscQueue.CAPACITY];
        int i = 0;
        for (int row = 0; row < 6; row++) {
            for (int col = 2; col < 9; col++) {
                out[i++] = row * 9 + col;
            }
        }
        return out;
    }

    private final Map<Block, Set<Player>> openViewers = new ConcurrentHashMap<>();

    private final Main plugin;

    public QueueGuiManager(Main plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, Block block) {
        if (!plugin.getPermissions().allows(player, Action.QUEUE_OPEN)) return;

        LavaPlayerManager apm = plugin.getAudioPlayerManager();

        if (!apm.hasActiveSession(block)
                && apm.getQueue(block) == null
                && block.getType() != Material.JUKEBOX) {
            return;
        }

        if (plugin.getJukeboxViewers().heldByOther(block, player)) {
            player.sendMessage("§c" + plugin.getMessageManager().get(player, "gui.queue.busy"));
            return;
        }

        int gen = apm.getGeneration(block);
        QueueGuiHolder holder = new QueueGuiHolder(block, gen);

        String title = plugin.getMessageManager().get(player, "gui.queue.title");
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title);
        holder.setInventory(inventory);

        DiscQueue queue = apm.getOrCreateQueue(block);
        render(player, inventory, block, queue);
        player.openInventory(inventory);
        plugin.getJukeboxViewers().claim(block, player);

        openViewers.computeIfAbsent(block, b -> ConcurrentHashMap.newKeySet()).add(player);
    }

    public void onClosed(Player player, Block block, Inventory inventory) {
        persist(inventory, block);
        Set<Player> viewers = openViewers.get(block);
        if (viewers != null) {
            viewers.remove(player);
            if (viewers.isEmpty()) openViewers.remove(block);
        }
        plugin.getJukeboxViewers().release(block, player);
    }

    public void onConfirmClosed(Player player, Block block) {
        plugin.getJukeboxViewers().release(block, player);
    }

    public void forceCloseFor(Block block) {
        plugin.getJukeboxViewers().clear(block);
        Set<Player> viewers = openViewers.remove(block);
        if (viewers == null) return;

        for (Player p : viewers) {
            if (p.isOnline()) p.closeInventory();
        }
    }

    // Only cells the player changed since the last paint are written: the queue may have
    // moved on meanwhile (dropped, ejected, advanced), and a whole-GUI copy resurrects discs.
    public void persist(Inventory inventory, Block block) {
        if (!(inventory.getHolder() instanceof QueueGuiHolder holder)) return;
        LavaPlayerManager apm = plugin.getAudioPlayerManager();
        DiscQueue queue = apm.getQueue(block);
        if (queue == null) return;

        ItemStack[] shown = holder.shown();
        int current = queue.getCurrentIndex();
        ItemStack takenCurrent = null;

        for (int i = 0; i < QUEUE_SLOTS.length; i++) {
            ItemStack now = copyOf(inventory.getItem(QUEUE_SLOTS[i]));
            if (Objects.equals(now, shown[i])) continue;

            if (!Objects.equals(queue.getSlot(i), shown[i])) {
                if (now != null) block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.1, 0.5), now);
                shown[i] = copyOf(queue.getSlot(i));
                inventory.setItem(QUEUE_SLOTS[i], shown[i]);
                continue;
            }

            if (i == current && shown[i] != null) takenCurrent = shown[i];
            queue.setSlot(i, now);
            shown[i] = copyOf(now);
        }

        if (takenCurrent != null) apm.releaseCurrentDisc(block, takenCurrent);
    }

    public void schedulePersist(Block block, Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> persist(inventory, block));
    }

    private static ItemStack copyOf(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private void paintCells(Inventory inventory, DiscQueue queue) {
        if (!(inventory.getHolder() instanceof QueueGuiHolder holder)) return;
        for (int i = 0; i < QUEUE_SLOTS.length; i++) {
            holder.shown()[i] = copyOf(queue.getSlot(i));
            inventory.setItem(QUEUE_SLOTS[i], holder.shown()[i]);
        }
    }

    public boolean hasViewer(Block block) {
        Set<Player> viewers = openViewers.get(block);
        return viewers != null && !viewers.isEmpty();
    }

    public void refreshOpen(Block block) {
        Set<Player> viewers = openViewers.get(block);
        if (viewers == null || viewers.isEmpty()) return;

        DiscQueue queue = plugin.getAudioPlayerManager().getOrCreateQueue(block);

        for (Player p : viewers) {
            if (!p.isOnline()) continue;
            Inventory inv = p.getOpenInventory().getTopInventory();
            if (!(inv.getHolder() instanceof QueueGuiHolder holder) || !holder.getBlock().equals(block)) continue;

            // A click still waiting for its next-tick persist must land before the repaint.
            persist(inv, block);
            paintCells(inv, queue);
            renderControls(p, inv, queue, block);
        }
    }

    public void openPlayConfirm(Player player, Block block, int queueIndex) {
        if (!plugin.getPermissions().allows(player, Action.QUEUE_PLAY)) return;

        LavaPlayerManager apm = plugin.getAudioPlayerManager();

        DiscQueue queue = apm.getQueue(block);
        if (queue == null) return;
        ItemStack disc = queue.getSlot(queueIndex);
        if (disc == null) return;

        ConfirmGuiHolder holder = new ConfirmGuiHolder(block, apm.getGeneration(block), queueIndex);
        String title = plugin.getMessageManager().get(player, "gui.queue.confirm.title");
        Inventory inv = Bukkit.createInventory(holder, CONFIRM_SIZE, title);
        holder.setInventory(inv);

        ItemStack filler = filler();
        for (int i = 0; i < CONFIRM_SIZE; i++) inv.setItem(i, filler);

        inv.setItem(CONFIRM_SLOT_YES, namedItem(Material.LIME_WOOL,
                plugin.getMessageManager().get(player, "gui.queue.confirm.accept")));
        inv.setItem(CONFIRM_SLOT_NO, namedItem(Material.RED_WOOL,
                plugin.getMessageManager().get(player, "gui.queue.confirm.decline")));
        inv.setItem(CONFIRM_SLOT_DISC, disc.clone());

        player.openInventory(inv);

        plugin.getJukeboxViewers().claim(block, player);
    }

    private ItemStack namedItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void refreshControls(Player player, Block block) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        if (!(inv.getHolder() instanceof QueueGuiHolder holder) || !holder.getBlock().equals(block)) return;
        DiscQueue queue = plugin.getAudioPlayerManager().getOrCreateQueue(block);
        renderControls(player, inv, queue, block);
    }

    private void render(Player player, Inventory inventory, Block block, DiscQueue queue) {
        ItemStack filler = filler();
        for (int slot : FILLER_SLOTS) {
            inventory.setItem(slot, filler);
        }

        paintCells(inventory, queue);

        renderControls(player, inventory, queue, block);
    }

    private void renderControls(Player player, Inventory inventory, DiscQueue queue) {
        inventory.setItem(SLOT_EXIT, exitItem(player));
        inventory.setItem(SLOT_CURRENT, currentItem(player, queue));
        inventory.setItem(SLOT_NEXT, nextItem(player, queue));
        inventory.setItem(SLOT_POLICY, policyItem(player, queue));
    }

    private void renderControls(Player player, Inventory inventory, DiscQueue queue, Block block) {
        renderControls(player, inventory, queue);
        inventory.setItem(SLOT_PAIR, pairItem(player, block));
    }

    private ItemStack pairItem(Player player, Block block) {
        if (!plugin.cdiscConfig().isSpeakerGroupEnabled()) return filler();

        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(block);
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        List<String> lore = new ArrayList<>();
        if (group == null) {
            meta.setDisplayName(plugin.getMessageManager().get(player, "gui.pair.button.unpaired"));
            lore.add(plugin.getMessageManager().get(player, "gui.pair.button.unpaired_hint"));
        } else {
            meta.setDisplayName(plugin.getMessageManager().get(player, "gui.pair.button.paired", group.name()));
            lore.add(plugin.getMessageManager().get(player,
                    group.isMain(block.getLocation()) ? "gui.pair.role_main" : "gui.pair.role_speaker"));
            lore.add(plugin.getMessageManager().get(player, "gui.pair.button.members", String.valueOf(group.size())));
            lore.add(plugin.getMessageManager().get(player, "gui.pair.button.paired_hint"));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack exitItem(Player player) {
        ItemStack item = HeadUtils.createHead(GuiHeads.EXIT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.getMessageManager().get(player, "gui.queue.exit.name"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack currentItem(Player player, DiscQueue queue) {
        ItemStack disc = queue.getCurrent();
        if (disc == null) {
            return placeholder(Material.RED_STAINED_GLASS_PANE,
                    plugin.getMessageManager().get(player, "gui.queue.current.none"));
        }
        return discDisplay(player, disc, "gui.queue.current.name", queue.getCurrentIndex());
    }

    private ItemStack nextItem(Player player, DiscQueue queue) {
        int cur = queue.getCurrentIndex();
        int next = cur >= 0 ? queue.nextFilledAfter(cur) : queue.firstFilled();
        if (next < 0) {
            return placeholder(Material.LIME_STAINED_GLASS_PANE,
                    plugin.getMessageManager().get(player, "gui.queue.next.none"));
        }
        return discDisplay(player, queue.getSlot(next), "gui.queue.next.name", next);
    }

    private ItemStack policyItem(Player player, DiscQueue queue) {
        PlayedPolicy policy = queue.getPolicy();
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getMessageManager().get(player, "gui.queue.policy.name"));

        List<String> lore = new ArrayList<>();
        lore.add(policyLine(player, "gui.queue.policy.nothing", policy == PlayedPolicy.NOTHING));
        lore.add(policyLine(player, "gui.queue.policy.eject", policy == PlayedPolicy.EJECT));
        lore.add(policyLine(player, "gui.queue.policy.move_to_end", policy == PlayedPolicy.MOVE_TO_END));
        lore.add(plugin.getMessageManager().get(player, "gui.queue.policy.hint"));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    private String policyLine(Player player, String key, boolean active) {
        String prefix = active ? "§a▶ §f" : "§8• §7";
        return prefix + plugin.getMessageManager().get(player, key);
    }

    private ItemStack discDisplay(Player player, ItemStack disc, String nameKey, int index) {
        ItemStack item = disc.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(plugin.getMessageManager().get(player, "gui.queue.lore_position", String.valueOf(index + 1)));
        meta.setDisplayName(plugin.getMessageManager().get(player, nameKey));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack filler() {
        return placeholder(Material.BLACK_STAINED_GLASS_PANE, " ");
    }

    private ItemStack placeholder(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isFillerSlot(int rawSlot) {
        return FILLER_SLOTS.contains(rawSlot);
    }

    public static boolean isControlSlot(int rawSlot) {
        return rawSlot == SLOT_EXIT || rawSlot == SLOT_NEXT || rawSlot == SLOT_CURRENT
                || rawSlot == SLOT_POLICY || rawSlot == SLOT_PAIR;
    }

    public static boolean isQueueSlot(int rawSlot) {
        if (rawSlot < 0 || rawSlot >= SIZE) return false;
        return !isFillerSlot(rawSlot) && !isControlSlot(rawSlot);
    }

    public static int queueIndexOf(int rawSlot) {
        for (int i = 0; i < QUEUE_SLOTS.length; i++) {
            if (QUEUE_SLOTS[i] == rawSlot) return i;
        }
        return -1;
    }
}
