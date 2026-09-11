package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.lyrics.HologramStyle;
import dev.valkdz.cdisc.lyrics.LyricsStyle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class LyricsGuiListener implements Listener {

    private final Main plugin;

    public LyricsGuiListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof LyricsGuiHolder holder)) return;
        e.setCancelled(true);

        if (e.getClickedInventory() == null
                || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;

        LyricsGuiManager gui = plugin.getLyricsGuiManager();
        int slot = e.getSlot();
        boolean forward = !e.isRightClick();

        if (slot == LyricsGuiManager.SLOT_BACK) {
            if (holder.isPreset()) {
                player.closeInventory();
            } else {
                plugin.getPlayerGuiManager().open(player, holder.getBlock());
            }
            return;
        }

        if (slot == LyricsGuiManager.SLOT_PAGE) {
            gui.openPage(player, holder,
                    (holder.getPage() + 1) % LyricsGuiManager.PAGES);
            return;
        }

        if (slot == LyricsGuiManager.SLOT_TAKE_PRESET && !holder.isPreset()) {
            HologramStyle mine = plugin.getHologramPresets().get(player.getUniqueId());
            if (mine == null) {
                player.sendMessage("§c" + plugin.getMessageManager()
                        .get(player, "command.preset.none_of_yours"));
                return;
            }
            gui.apply(player, holder, mine);
            gui.refresh(player);
            return;
        }

        if (slot == LyricsGuiManager.SLOT_RESET) {
            if (!gui.isCustomised(holder)) return;
            gui.reset(player, holder);
            gui.refresh(player);
            return;
        }

        HologramStyle style = gui.styleOf(holder);
        HologramStyle edited = holder.getPage() == 0
                ? editColors(style, slot, forward)
                : editLayout(style, slot, forward, holder.isPreset());

        if (edited == null || edited.equals(style)) return;

        gui.apply(player, holder, edited);
        gui.refresh(player);
    }

    private HologramStyle editColors(HologramStyle style, int slot, boolean forward) {
        if (slot == LyricsGuiManager.SLOT_BACKGROUND_COLOR) {
            int index = LyricsGuiManager.indexOfRgb(style.backgroundColor());
            return style.withBackgroundColor(
                    LyricsGuiManager.COLOR_RGB[nextColor(index, forward)]);
        }
        if (slot == LyricsGuiManager.SLOT_BACKGROUND_OPACITY) {
            return style.withBackgroundOpacity(step(style.backgroundOpacity(), forward,
                    LyricsGuiManager.OPACITY_STEP));
        }
        if (slot == LyricsGuiManager.SLOT_BRIGHTNESS) {
            return style.withBrightness(nextBrightness(style.brightness(), forward));
        }

        if (slot == LyricsGuiManager.SLOT_TEXT_COLOR) {
            return style.withTextPrefix(cycled(style.textPrefix(), forward));
        }
        if (slot == LyricsGuiManager.SLOT_TEXT_OPACITY) {
            return style.withTextOpacity(step(style.textOpacity(), forward,
                    LyricsGuiManager.OPACITY_STEP));
        }
        if (slot == LyricsGuiManager.SLOT_CURRENT_COLOR) {
            return style.withCurrentPrefix(cycled(style.currentPrefix(), forward));
        }
        if (slot == LyricsGuiManager.SLOT_CURRENT_OPACITY) {
            return style.withCurrentOpacity(step(style.currentOpacity(), forward,
                    LyricsGuiManager.OPACITY_STEP));
        }

        int text = slot - LyricsGuiManager.SLOT_TEXT_FORMAT_FIRST;
        if (text >= 0 && text < LyricsGuiManager.FORMATS.length) {
            return style.withTextPrefix(LyricsStyle.toggleFormat(
                    style.textPrefix(), LyricsGuiManager.FORMATS[text]));
        }

        int current = slot - LyricsGuiManager.SLOT_CURRENT_FORMAT_FIRST;
        if (current >= 0 && current < LyricsGuiManager.FORMATS.length) {
            return style.withCurrentPrefix(LyricsStyle.toggleFormat(
                    style.currentPrefix(), LyricsGuiManager.FORMATS[current]));
        }

        return null;
    }

    private HologramStyle editLayout(HologramStyle style, int slot, boolean forward, boolean preset) {
        if (slot == LyricsGuiManager.SLOT_SIZE) {
            return style.withSize(wrap(style.size() + (forward ? 1 : -1), 1, 10));
        }
        if (slot == LyricsGuiManager.SLOT_HEIGHT) {
            return style.withHeight(style.height() + (forward ? 0.1 : -0.1));
        }
        if (slot == LyricsGuiManager.SLOT_LINE_WIDTH) {
            return style.withLineWidth(step(style.lineWidth(), forward, 20));
        }
        if (slot == LyricsGuiManager.SLOT_LINES_BEFORE) {
            return style.withLinesBefore(wrap(style.linesBefore() + (forward ? 1 : -1), 0, 6));
        }
        if (slot == LyricsGuiManager.SLOT_LINES_AFTER) {
            return style.withLinesAfter(wrap(style.linesAfter() + (forward ? 1 : -1), 0, 6));
        }
        if (slot == LyricsGuiManager.SLOT_SHADOW) {
            return style.withShadow(!style.shadow());
        }
        if (slot == LyricsGuiManager.SLOT_SEE_THROUGH) {
            return style.withSeeThrough(!style.seeThrough());
        }
        if (slot == LyricsGuiManager.SLOT_COUNTDOWN) {
            return style.withCountdown(!style.countdown());
        }

        if (preset) return null;

        if (slot == LyricsGuiManager.SLOT_FADE_TICKS) {
            return style.withFadeTicks(step(style.fadeTicks(), forward, 1));
        }
        if (slot == LyricsGuiManager.SLOT_SLIDE) {
            return style.withSlide(!style.slide());
        }
        return null;
    }

    private static String cycled(String prefix, boolean forward) {
        int index = LyricsGuiManager.indexOfColor(LyricsStyle.colorCode(prefix));
        return LyricsStyle.withColor(prefix, LyricsGuiManager.COLORS[nextColor(index, forward)]);
    }

    private static int nextColor(int index, boolean forward) {
        int count = LyricsGuiManager.COLORS.length;
        if (index < 0) return 0;
        return (index + (forward ? 1 : count - 1)) % count;
    }

    private static int step(int value, boolean up, int by) {
        return value + (up ? by : -by);
    }

    private static int wrap(int value, int min, int max) {
        if (value > max) return min;
        if (value < min) return max;
        return value;
    }

    private static int nextBrightness(int brightness, boolean up) {
        int next = brightness + (up ? 1 : -1);
        if (next > 15) return HologramStyle.BRIGHTNESS_WORLD;
        if (next < HologramStyle.BRIGHTNESS_WORLD) return 15;
        return next;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof LyricsGuiHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof LyricsGuiHolder
                && e.getPlayer() instanceof Player player) {
            plugin.getHologramPreview().hide(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getHologramPreview().hide(e.getPlayer());
    }
}
