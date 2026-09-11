package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.lyrics.HologramStyle;
import dev.valkdz.cdisc.lyrics.LyricsStyle;
import dev.valkdz.cdisc.permission.Action;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LyricsGuiManager {

    public static final int SIZE = 54;
    public static final int PAGES = 2;

    public static final int SLOT_TITLE = 4;

    public static final int SLOT_BACKGROUND_HEADER = 9;
    public static final int SLOT_BACKGROUND_COLOR = 11;
    public static final int SLOT_BACKGROUND_OPACITY = 12;
    public static final int SLOT_BRIGHTNESS = 14;

    public static final int SLOT_TEXT_HEADER = 18;
    public static final int SLOT_TEXT_COLOR = 19;
    public static final int SLOT_TEXT_OPACITY = 20;
    public static final int SLOT_TEXT_FORMAT_FIRST = 22;

    public static final int SLOT_CURRENT_HEADER = 27;
    public static final int SLOT_CURRENT_COLOR = 28;
    public static final int SLOT_CURRENT_OPACITY = 29;
    public static final int SLOT_CURRENT_FORMAT_FIRST = 31;

    public static final int SLOT_PLACEMENT_HEADER = 9;
    public static final int SLOT_SIZE = 11;
    public static final int SLOT_HEIGHT = 12;
    public static final int SLOT_LINE_WIDTH = 13;
    public static final int SLOT_LINES_BEFORE = 14;
    public static final int SLOT_LINES_AFTER = 15;

    public static final int SLOT_LOOK_HEADER = 18;
    public static final int SLOT_SHADOW = 20;
    public static final int SLOT_SEE_THROUGH = 21;

    public static final int SLOT_ANIMATION_HEADER = 27;
    public static final int SLOT_FADE_TICKS = 29;
    public static final int SLOT_SLIDE = 30;
    public static final int SLOT_COUNTDOWN = 31;

    public static final int SLOT_RESET = 45;

    public static final int SLOT_PAGE = 49;
    public static final int SLOT_BACK = 53;

    public static final char[] FORMATS = {'l', 'o', 'n', 'm', 'k'};

    private static final String[] FORMAT_KEYS = {"bold", "italic", "underline", "strike", "magic"};

    public static final char[] COLORS = {
            'f', '7', '8', '0', 'e', '6', 'c', '4',
            'a', '2', 'b', '3', '9', '1', 'd', '5'};

    private static final Material[] COLOR_WOOL = {
            Material.WHITE_WOOL, Material.LIGHT_GRAY_WOOL, Material.GRAY_WOOL, Material.BLACK_WOOL,
            Material.YELLOW_WOOL, Material.ORANGE_WOOL, Material.RED_WOOL, Material.RED_WOOL,
            Material.LIME_WOOL, Material.GREEN_WOOL, Material.LIGHT_BLUE_WOOL, Material.CYAN_WOOL,
            Material.BLUE_WOOL, Material.BLUE_WOOL, Material.MAGENTA_WOOL, Material.PURPLE_WOOL};

    public static final int[] COLOR_RGB = {
            0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000, 0xFFFF55, 0xFFAA00, 0xFF5555, 0xAA0000,
            0x55FF55, 0x00AA00, 0x55FFFF, 0x00AAAA, 0x5555FF, 0x0000AA, 0xFF55FF, 0xAA00AA};

    public static final int OPACITY_STEP = 25;

    private final Main plugin;

    public LyricsGuiManager(Main plugin) {
        this.plugin = plugin;
    }

    public void openPreset(Player player) {
        if (!plugin.getPermissions().allows(player, Action.LYRICS_PRESET)) return;

        open(player, new LyricsGuiHolder(player.getUniqueId(), 0));
    }

    public void openPage(Player player, LyricsGuiHolder from, int page) {
        open(player, new LyricsGuiHolder(from.getOwner(), page));
    }

    private void open(Player player, LyricsGuiHolder holder) {
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.getMessageManager().get(player, "gui.lyrics_look.preset_title"));
        holder.setInventory(inventory);

        fill(player, holder, inventory);
        player.openInventory(inventory);

        updatePreview(player, holder);
    }

    public void refresh(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof LyricsGuiHolder holder)) return;

        fill(player, holder, holder.getInventory());
        player.updateInventory();

        updatePreview(player, holder);
    }

    private void updatePreview(Player player, LyricsGuiHolder holder) {
        if (plugin.getLyricsDisplay().showsOwnStyleTo(player)) {
            plugin.getHologramPreview().hide(player);
            return;
        }
        plugin.getHologramPreview().show(player, styleOf(holder));
    }

    public HologramStyle styleOf(LyricsGuiHolder holder) {
        return plugin.getHologramPresets().getOrDefault(holder.getOwner());
    }

    public void apply(Player player, LyricsGuiHolder holder, HologramStyle style) {
        plugin.getHologramPresets().set(holder.getOwner(), style);
        plugin.presetChanged(player);
    }

    public boolean isCustomised(LyricsGuiHolder holder) {
        return plugin.getHologramPresets().has(holder.getOwner());
    }

    public void reset(Player player, LyricsGuiHolder holder) {
        plugin.getHologramPresets().clear(holder.getOwner());
        plugin.presetChanged(player);
    }

    private void fill(Player player, LyricsGuiHolder holder, Inventory inventory) {
        HologramStyle style = styleOf(holder);

        ItemStack filler = simple(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        inventory.setItem(SLOT_TITLE, titleItem(player, style));

        if (holder.getPage() == 0) {
            fillColors(player, inventory, style);
        } else {
            fillLayout(player, inventory, style);
        }

        inventory.setItem(SLOT_RESET, resetItem(player, holder));
        inventory.setItem(SLOT_PAGE, pageItem(player, holder));
        inventory.setItem(SLOT_BACK, simple(Material.ARROW,
                plugin.getMessageManager().get(player, "gui.lyrics_look.close"), List.of()));
    }

    private void fillColors(Player player, Inventory inventory, HologramStyle style) {
        inventory.setItem(SLOT_BACKGROUND_HEADER, header(player, Material.BLACK_STAINED_GLASS_PANE,
                "gui.lyrics_look.header_background"));
        inventory.setItem(SLOT_BACKGROUND_COLOR, rgbColorItem(player, style.backgroundColor()));
        inventory.setItem(SLOT_BACKGROUND_OPACITY, opacityItem(player, Material.BLACK_STAINED_GLASS,
                "gui.lyrics_look.background_opacity", style.backgroundOpacity()));
        inventory.setItem(SLOT_BRIGHTNESS, brightnessItem(player, style.brightness()));

        inventory.setItem(SLOT_TEXT_HEADER, header(player, Material.PAPER,
                "gui.lyrics_look.header_text"));
        inventory.setItem(SLOT_TEXT_COLOR, colorItem(player, style.textPrefix(), false));
        inventory.setItem(SLOT_TEXT_OPACITY, opacityItem(player, Material.GLASS,
                "gui.lyrics_look.text_opacity", style.textOpacity()));
        for (int i = 0; i < FORMATS.length; i++) {
            inventory.setItem(SLOT_TEXT_FORMAT_FIRST + i, formatItem(player, style.textPrefix(), i));
        }

        inventory.setItem(SLOT_CURRENT_HEADER, header(player, Material.NAME_TAG,
                "gui.lyrics_look.header_current"));
        inventory.setItem(SLOT_CURRENT_COLOR, colorItem(player, style.currentPrefix(), true));
        inventory.setItem(SLOT_CURRENT_OPACITY, opacityItem(player, Material.GLASS,
                "gui.lyrics_look.current_opacity", style.currentOpacity()));
        for (int i = 0; i < FORMATS.length; i++) {
            inventory.setItem(SLOT_CURRENT_FORMAT_FIRST + i, formatItem(player, style.currentPrefix(), i));
        }
    }

    private void fillLayout(Player player, Inventory inventory, HologramStyle style) {
        inventory.setItem(SLOT_PLACEMENT_HEADER, header(player, Material.SCAFFOLDING,
                "gui.lyrics_look.header_placement"));
        inventory.setItem(SLOT_SIZE, numberItem(player, Material.SPYGLASS,
                "gui.lyrics_look.size", String.valueOf(style.size()), "hint_cycle"));
        inventory.setItem(SLOT_HEIGHT, numberItem(player, Material.LADDER,
                "gui.lyrics_look.height",
                String.format(Locale.ROOT, "%.1f", style.height()), "hint_adjust"));
        inventory.setItem(SLOT_LINE_WIDTH, numberItem(player, Material.STRING,
                "gui.lyrics_look.line_width", String.valueOf(style.lineWidth()), "hint_adjust"));
        inventory.setItem(SLOT_LINES_BEFORE, numberItem(player, Material.BOOK,
                "gui.lyrics_look.lines_before", String.valueOf(style.linesBefore()), "hint_cycle"));
        inventory.setItem(SLOT_LINES_AFTER, numberItem(player, Material.WRITABLE_BOOK,
                "gui.lyrics_look.lines_after", String.valueOf(style.linesAfter()), "hint_cycle"));

        inventory.setItem(SLOT_LOOK_HEADER, header(player, Material.ITEM_FRAME,
                "gui.lyrics_look.header_look"));
        inventory.setItem(SLOT_SHADOW, toggleItem(player, "gui.lyrics_look.shadow", style.shadow(), null));
        inventory.setItem(SLOT_SEE_THROUGH, toggleItem(player, "gui.lyrics_look.see_through",
                style.seeThrough(), null));

        inventory.setItem(SLOT_ANIMATION_HEADER, header(player, Material.CLOCK,
                "gui.lyrics_look.header_animation"));

        inventory.setItem(SLOT_FADE_TICKS, numberItem(player, Material.CLOCK,
                "gui.lyrics_look.fade_ticks", String.valueOf(style.fadeTicks()), "hint_adjust"));
        inventory.setItem(SLOT_SLIDE, toggleItem(player, "gui.lyrics_look.slide",
                style.slide(), null));
        inventory.setItem(SLOT_COUNTDOWN, toggleItem(player, "gui.lyrics_look.countdown",
                style.countdown(), null));
    }

    private ItemStack titleItem(Player player, HologramStyle style) {
        LyricsStyle colours = style.lyricsStyle();

        List<String> lore = new ArrayList<>();
        lore.add("§7" + plugin.getMessageManager().get(player, "gui.lyrics_look.preset_lore"));
        lore.add("");
        lore.add(colours.other() + plugin.getMessageManager().get(player, "gui.lyrics_look.sample_other"));
        lore.add(colours.current() + plugin.getMessageManager().get(player, "gui.lyrics_look.sample_current"));
        lore.add(colours.other() + plugin.getMessageManager().get(player, "gui.lyrics_look.sample_other"));

        return simple(Material.PLAYER_HEAD,
                plugin.getMessageManager().get(player, "gui.lyrics_look.preset_title"), lore);
    }

    private ItemStack pageItem(Player player, LyricsGuiHolder holder) {
        List<String> lore = List.of("§8" + plugin.getMessageManager()
                .get(player, "gui.lyrics_look.hint_toggle"));

        return simple(Material.COMPASS, "§f" + plugin.getMessageManager().get(player,
                holder.getPage() == 0 ? "gui.lyrics_look.page_layout" : "gui.lyrics_look.page_colors"),
                lore);
    }

    private ItemStack header(Player player, Material material, String key) {
        return simple(material, "§e§l" + plugin.getMessageManager().get(player, key), List.of());
    }

    private ItemStack opacityItem(Player player, Material material, String key, int opacity) {
        List<String> lore = List.of(
                "§7" + plugin.getMessageManager().get(player, "gui.lyrics_look.value",
                        percent(opacity), opacity),
                "",
                "§8" + plugin.getMessageManager().get(player, "gui.lyrics_look.hint_adjust"));

        return simple(material, "§f" + plugin.getMessageManager().get(player, key), lore);
    }

    private ItemStack numberItem(Player player, Material material, String key,
                                 String value, String hint) {
        List<String> lore = List.of(
                "§7" + plugin.getMessageManager().get(player, "gui.lyrics_look.value_plain", value),
                "",
                "§8" + plugin.getMessageManager().get(player, "gui.lyrics_look." + hint));

        return simple(material, "§f" + plugin.getMessageManager().get(player, key), lore);
    }

    private ItemStack toggleItem(Player player, String key, boolean on, String noteKey) {
        List<String> lore = new ArrayList<>();
        lore.add("§7" + onOff(player, on));
        lore.add("");
        if (noteKey != null) lore.add("§8" + plugin.getMessageManager().get(player, noteKey));
        lore.add("§8" + plugin.getMessageManager().get(player, "gui.lyrics_look.hint_toggle"));

        return simple(on ? Material.LIME_DYE : Material.GRAY_DYE,
                (on ? "§a" : "§8") + plugin.getMessageManager().get(player, key), lore);
    }

    private String onOff(Player player, boolean on) {
        return plugin.getMessageManager().get(player,
                on ? "gui.lyrics_look.state_on" : "gui.lyrics_look.state_off");
    }

    private ItemStack brightnessItem(Player player, int brightness) {
        String value = brightness < 0
                ? plugin.getMessageManager().get(player, "gui.lyrics_look.brightness_world")
                : String.valueOf(brightness);

        List<String> lore = List.of(
                "§7" + plugin.getMessageManager().get(player, "gui.lyrics_look.value_plain", value),
                "",

                "§8" + plugin.getMessageManager().get(player, "gui.lyrics_look.brightness_note"),
                "§8" + plugin.getMessageManager().get(player, "gui.lyrics_look.hint_cycle"));

        return simple(brightness < 0 ? Material.GLOWSTONE_DUST : Material.GLOWSTONE,
                "§f" + plugin.getMessageManager().get(player, "gui.lyrics_look.brightness"), lore);
    }

    private ItemStack rgbColorItem(Player player, int rgb) {
        int index = indexOfRgb(rgb);

        List<String> lore = List.of(
                "§7" + plugin.getMessageManager().get(player, "gui.lyrics_look.value_plain",
                        "§" + (index < 0 ? 'f' : COLORS[index]) + colorName(player, index)),
                "",
                "§8" + plugin.getMessageManager().get(player, "gui.lyrics_look.hint_cycle"));

        return simple(index < 0 ? Material.BLACK_WOOL : COLOR_WOOL[index],
                "§f" + plugin.getMessageManager().get(player, "gui.lyrics_look.background_color"), lore);
    }

    private ItemStack colorItem(Player player, String prefix, boolean current) {
        int index = indexOfColor(LyricsStyle.colorCode(prefix));

        List<String> lore = List.of(
                "§7" + plugin.getMessageManager().get(player, "gui.lyrics_look.value_plain",
                        "§" + (index < 0 ? 'f' : COLORS[index]) + colorName(player, index)),
                "",
                "§8" + plugin.getMessageManager().get(player, "gui.lyrics_look.hint_cycle"));

        return simple(index < 0 ? Material.WHITE_WOOL : COLOR_WOOL[index],
                "§f" + plugin.getMessageManager().get(player,
                        current ? "gui.lyrics_look.color_current" : "gui.lyrics_look.color_text"),
                lore);
    }

    private String colorName(Player player, int index) {
        return plugin.getMessageManager().get(player, index < 0
                ? "gui.lyrics_look.color_custom"
                : "gui.lyrics_look.color_" + COLORS[index]);
    }

    private ItemStack formatItem(Player player, String prefix, int index) {
        char code = FORMATS[index];
        boolean on = LyricsStyle.hasFormat(prefix, code);

        String label = plugin.getMessageManager().get(player,
                "gui.lyrics_look.format_" + FORMAT_KEYS[index]);

        List<String> lore = List.of(
                "§7" + onOff(player, on),
                "",
                "§8" + plugin.getMessageManager().get(player, "gui.lyrics_look.hint_toggle"));

        String worn = on && code != 'k' ? "§a§" + code : (on ? "§a" : "§8");
        return simple(on ? Material.LIME_DYE : Material.GRAY_DYE, worn + label, lore);
    }

    private ItemStack resetItem(Player player, LyricsGuiHolder holder) {
        boolean customised = isCustomised(holder);

        List<String> lore = List.of("§7" + plugin.getMessageManager().get(player,
                customised ? "gui.lyrics_look.reset_lore" : "gui.lyrics_look.reset_already"));

        return simple(customised ? Material.BARRIER : Material.STRUCTURE_VOID,
                "§c" + plugin.getMessageManager().get(player, "gui.lyrics_look.reset"), lore);
    }

    public static int indexOfColor(char code) {
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i] == code) return i;
        }
        return -1;
    }

    public static int indexOfRgb(int rgb) {
        for (int i = 0; i < COLOR_RGB.length; i++) {
            if (COLOR_RGB[i] == (rgb & 0xFFFFFF)) return i;
        }
        return -1;
    }

    private static int percent(int opacity) {
        return Math.round(opacity * 100f / 255f);
    }

    private static ItemStack simple(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
