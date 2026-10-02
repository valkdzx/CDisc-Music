package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.feature.local.LocalMusicLibrary;
import dev.valkdz.cdisc.feature.local.LocalTrackSettings;
import dev.valkdz.cdisc.permission.Action;
import dev.valkdz.cdisc.util.Chat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalConfigGuiManager {

    public static final int MAIN_SIZE = 45;
    public static final int SLOT_FILE = 4;
    public static final int SLOT_NAME = 10;
    public static final int SLOT_NAME_RESET = 19;
    public static final int SLOT_VOLUME = 12;
    public static final int SLOT_VOLUME_RESET = 21;
    public static final int SLOT_PERMISSION = 14;
    public static final int SLOT_PERMISSION_RESET = 23;
    public static final int SLOT_LYRICS = 16;
    public static final int SLOT_LYRICS_TOGGLE = 25;
    public static final int SLOT_META_TITLE = 29;
    public static final int SLOT_META_AUTHOR = 38;
    public static final int SLOT_META_TEXT = 33;
    public static final int SLOT_ONE_LINE = 42;

    public static final int LYRICS_SIZE = 54;
    public static final int GRID_ROWS = 4;
    private static final int[] GRID_COLUMNS = {1, 3, 5, 7};
    public static final int PER_PAGE = GRID_ROWS * GRID_COLUMNS.length;
    public static final int SEPARATOR_ROW_START = 36;
    public static final int SLOT_PREVIOUS = 45;
    public static final int SLOT_CLEAR = 48;
    public static final int SLOT_DONE = 49;
    public static final int SLOT_SYNC = 50;
    public static final int SLOT_NEXT = 53;

    public static final int VOLUME_STEP = 10;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_META_LENGTH = 128;
    private static final int LORE_WIDTH = 40;
    private static final int TITLE_FILE_LENGTH = 24;

    public enum Kind {
        NAME,
        PERMISSION,
        LINE,
        TITLE,
        AUTHOR,
        TEXT
    }

    public record Pending(String file, Kind kind, int index, boolean insert) {
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private final Main plugin;

    public LocalConfigGuiManager(Main plugin) {
        this.plugin = plugin;
    }

    public LocalMusicLibrary library() {
        return plugin.getLocalMusic();
    }

    public boolean mayEdit(Player player) {
        return plugin.getPermissions().allows(player, Action.ADMIN_LOCAL_FILES);
    }

    public boolean exists(String file) {
        LocalMusicLibrary library = library();
        return library != null && library.isEnabled() && library.index().contains(file);
    }

    public LocalTrackSettings settings(String file) {
        LocalTrackSettings own = library().settingsOf(file);
        return own == null ? LocalTrackSettings.EMPTY : own;
    }

    public boolean save(Player player, String file, LocalTrackSettings updated) {
        try {
            library().saveSettings(file, updated);
            return true;
        } catch (IOException | RuntimeException e) {
            player.sendMessage(msg(player, "local_config.save_failed",
                    file + LocalTrackSettings.EXTENSION, String.valueOf(e.getMessage())));
            return false;
        }
    }

    public int pageCount(LocalTrackSettings settings) {
        return settings.lines().size() / PER_PAGE + 1;
    }

    public int pageOf(int index) {
        return Math.max(0, index) / PER_PAGE;
    }

    public static int gridIndex(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        if (row >= GRID_ROWS) return -1;
        for (int c = 0; c < GRID_COLUMNS.length; c++) {
            if (GRID_COLUMNS[c] == column) return c * GRID_ROWS + row;
        }
        return -1;
    }

    private static int gridSlot(int index) {
        return (index % GRID_ROWS) * 9 + GRID_COLUMNS[index / GRID_ROWS];
    }

    public void open(Player player, String file) {
        if (!mayEdit(player)) return;

        LocalTrackSettings settings = settings(file);
        LocalConfigGuiHolder holder = new LocalConfigGuiHolder(
                LocalConfigGuiHolder.Mode.MAIN, file, 0, false);
        Inventory inventory = Bukkit.createInventory(holder, MAIN_SIZE,
                msg(player, "local_config.title", shortName(file)));
        holder.setInventory(inventory);

        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < MAIN_SIZE; i++) inventory.setItem(i, filler);

        inventory.setItem(SLOT_FILE, item(Material.MUSIC_DISC_CAT, "§f" + file,
                List.of(msg(player, "local_config.file_lore", file + LocalTrackSettings.EXTENSION))));

        inventory.setItem(SLOT_NAME, item(Material.NAME_TAG,
                settings.name() == null ? msg(player, "local_config.name_none")
                        : msg(player, "local_config.name", settings.name()),
                List.of(msg(player, "local_config.name_hint"))));
        inventory.setItem(SLOT_NAME_RESET, resetItem(player, "local_config.reset_name"));

        inventory.setItem(SLOT_VOLUME, volumeItem(player, settings));
        inventory.setItem(SLOT_VOLUME_RESET, resetItem(player, "local_config.reset_volume"));

        inventory.setItem(SLOT_PERMISSION, item(Material.TRIPWIRE_HOOK,
                settings.permissions().isEmpty() ? msg(player, "local_config.permission_none")
                        : msg(player, "local_config.permission", String.join(", ", settings.permissions())),
                List.of(msg(player, "local_config.permission_hint"))));
        inventory.setItem(SLOT_PERMISSION_RESET, resetItem(player, "local_config.reset_permission"));

        inventory.setItem(SLOT_LYRICS, item(Material.WRITABLE_BOOK,
                msg(player, "local_config.lyrics", String.valueOf(settings.lines().size())),
                List.of(msg(player, "local_config.lyrics_hint"))));
        inventory.setItem(SLOT_LYRICS_TOGGLE, item(
                settings.lyricsEnabled() ? Material.LIME_DYE : Material.GRAY_DYE,
                msg(player, settings.lyricsEnabled() ? "local_config.lyrics_on" : "local_config.lyrics_off"),
                List.of(msg(player, "local_config.toggle_hint"))));

        LocalTrackSettings.Meta meta = settings.meta();
        boolean oneLine = meta.showsOneLine();
        inventory.setItem(SLOT_META_TITLE, metaItem(player, Material.OAK_SIGN, meta.title(),
                "local_config.meta_title", "local_config.meta_title_none", !oneLine));
        inventory.setItem(SLOT_META_AUTHOR, metaItem(player, Material.PLAYER_HEAD, meta.author(),
                "local_config.meta_author", "local_config.meta_author_none", !oneLine));
        inventory.setItem(SLOT_META_TEXT, metaItem(player, Material.BIRCH_SIGN, meta.text(),
                "local_config.meta_text", "local_config.meta_text_none", oneLine));

        List<String> modeLore = new ArrayList<>();
        if (meta.oneLine() && meta.text() == null) modeLore.add(msg(player, "local_config.one_line_needs_text"));
        modeLore.add(msg(player, "local_config.meta_note"));
        modeLore.add(msg(player, "local_config.toggle_hint"));
        inventory.setItem(SLOT_ONE_LINE, item(meta.oneLine() ? Material.REPEATER : Material.COMPARATOR,
                msg(player, meta.oneLine() ? "local_config.one_line_on" : "local_config.one_line_off"),
                modeLore));

        player.openInventory(inventory);
    }

    private ItemStack metaItem(Player player, Material material, String value,
                               String setKey, String unsetKey, boolean shown) {
        return item(material,
                value == null ? msg(player, unsetKey) : msg(player, setKey, value),
                List.of(msg(player, shown ? "local_config.meta_shown" : "local_config.meta_hidden"),
                        msg(player, "local_config.meta_hint")));
    }

    public int currentVolume(LocalTrackSettings settings) {
        return settings.volume() != null ? settings.volume() : plugin.cdiscConfig().getVolume();
    }

    private ItemStack volumeItem(Player player, LocalTrackSettings settings) {
        int volume = currentVolume(settings);
        ItemStack item = item(Material.NOTE_BLOCK,
                settings.volume() == null
                        ? msg(player, "local_config.volume_default", String.valueOf(volume))
                        : msg(player, "local_config.volume", String.valueOf(volume)),
                List.of(msg(player, "local_config.volume_hint")));
        item.setAmount(Math.max(1, Math.min(LocalTrackSettings.MAX_VOLUME / VOLUME_STEP,
                Math.round(volume / (float) VOLUME_STEP))));
        return item;
    }

    public void openLyrics(Player player, String file, int page, boolean clearArmed) {
        if (!mayEdit(player)) return;

        LocalTrackSettings settings = settings(file);
        List<LocalTrackSettings.Line> lines = settings.lines();
        int shown = Math.max(0, Math.min(page, pageCount(settings) - 1));

        LocalConfigGuiHolder holder = new LocalConfigGuiHolder(
                LocalConfigGuiHolder.Mode.LYRICS, file, shown, clearArmed);
        Inventory inventory = Bukkit.createInventory(holder, LYRICS_SIZE,
                msg(player, "local_config.lyrics_title",
                        String.valueOf(shown + 1), String.valueOf(pageCount(settings))));
        holder.setInventory(inventory);

        int first = shown * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int index = first + i;
            if (index < lines.size()) {
                inventory.setItem(gridSlot(i), lineItem(player, index, lines.get(index)));
            } else if (index == lines.size()) {
                inventory.setItem(gridSlot(i), item(Material.LIME_DYE,
                        msg(player, "local_config.add"), List.of(msg(player, "local_config.add_hint"))));
            }
        }

        ItemStack separator = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = SEPARATOR_ROW_START; i < SEPARATOR_ROW_START + 9; i++) {
            inventory.setItem(i, separator);
        }

        if (shown > 0) {
            inventory.setItem(SLOT_PREVIOUS, item(Material.WHITE_STAINED_GLASS_PANE,
                    msg(player, "local_config.previous_page"), List.of()));
        }
        if (shown < pageCount(settings) - 1) {
            inventory.setItem(SLOT_NEXT, item(Material.WHITE_STAINED_GLASS_PANE,
                    msg(player, "local_config.next_page"), List.of()));
        }
        inventory.setItem(SLOT_CLEAR, item(Material.RED_CONCRETE,
                msg(player, clearArmed ? "local_config.clear_confirm" : "local_config.clear"), List.of()));
        inventory.setItem(SLOT_DONE, item(Material.LIME_CONCRETE, msg(player, "local_config.done"), List.of()));
        inventory.setItem(SLOT_SYNC, item(Material.CLOCK,
                msg(player, settings.syncWithTime() ? "local_config.sync_on" : "local_config.sync_off"),
                List.of(msg(player, "local_config.toggle_hint"))));

        player.openInventory(inventory);
    }

    private ItemStack lineItem(Player player, int index, LocalTrackSettings.Line line) {
        String number = String.valueOf(index + 1);
        String title = line.timed()
                ? msg(player, "local_config.line", number, LocalTrackSettings.formatTime(line.timeMs()))
                : msg(player, "local_config.line_untimed", number);

        List<String> lore = new ArrayList<>(wrap(line.text().isEmpty() ? "§8…" : line.text()));
        lore.add("");
        lore.add(msg(player, "local_config.line_hint"));

        ItemStack item = item(line.timed() ? Material.PAPER : Material.MAP, title, lore);
        item.setAmount(Math.min(64, index + 1));
        return item;
    }

    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() > 0 && current.length() + word.length() + 1 > LORE_WIDTH) {
                out.add("§f" + current);
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) out.add("§f" + current);
        return out;
    }

    public void prompt(Player player, Pending ask) {
        pending.put(player.getUniqueId(), ask);
        player.closeInventory();

        switch (ask.kind()) {
            case NAME -> {
                player.sendMessage(msg(player, "local_config.name_prompt"));
                String now = settings(ask.file()).name();
                if (now != null) offerCurrent(player, now);
            }
            case PERMISSION -> {
                player.sendMessage(msg(player, "local_config.permission_prompt"));
                List<String> now = settings(ask.file()).permissions();
                if (!now.isEmpty()) offerCurrent(player, String.join(", ", now));
            }
            case TITLE, AUTHOR, TEXT -> {
                LocalTrackSettings.Meta meta = settings(ask.file()).meta();
                String now = switch (ask.kind()) {
                    case TITLE -> meta.title();
                    case AUTHOR -> meta.author();
                    default -> meta.text();
                };
                player.sendMessage(msg(player, switch (ask.kind()) {
                    case TITLE -> "local_config.title_prompt";
                    case AUTHOR -> "local_config.author_prompt";
                    default -> "local_config.text_prompt";
                }));
                if (now != null) offerCurrent(player, now);
            }
            case LINE -> {
                player.sendMessage(msg(player, "local_config.line_prompt"));
                List<LocalTrackSettings.Line> lines = settings(ask.file()).lines();
                if (!ask.insert() && ask.index() < lines.size()) {
                    offerCurrent(player, lines.get(ask.index()).asTyped());
                }
            }
        }
    }

    private void offerCurrent(Player player, String value) {
        Chat.send(player, Chat.suggest(msg(player, "local_config.current", value), value,
                msg(player, "local_config.current_hover")));
    }

    public Pending pendingOf(Player player) {
        return pending.get(player.getUniqueId());
    }

    public void cancel(Player player) {
        pending.remove(player.getUniqueId());
    }

    private ItemStack resetItem(Player player, String key) {
        return item(Material.BARRIER, msg(player, key), List.of());
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String shortName(String file) {
        int slash = file.lastIndexOf('/');
        String name = slash < 0 ? file : file.substring(slash + 1);
        return name.length() <= TITLE_FILE_LENGTH ? name : name.substring(0, TITLE_FILE_LENGTH - 1) + "…";
    }

    public String msg(Player player, String key, Object... args) {
        return plugin.getMessageManager().get(player, key, args);
    }
}
