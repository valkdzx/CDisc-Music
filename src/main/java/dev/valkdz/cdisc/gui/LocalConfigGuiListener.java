package dev.valkdz.cdisc.gui;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.feature.local.LocalTrackSettings;
import dev.valkdz.cdisc.gui.LocalConfigGuiManager.Kind;
import dev.valkdz.cdisc.gui.LocalConfigGuiManager.Pending;
import dev.valkdz.cdisc.util.Chat;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;

public final class LocalConfigGuiListener implements Listener {

    private final Main plugin;

    public LocalConfigGuiListener(Main plugin) {
        this.plugin = plugin;
    }

    private LocalConfigGuiManager gui() {
        return plugin.getLocalConfigGuiManager();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof LocalConfigGuiHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;

        e.setCancelled(true);

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) return;

        String file = holder.getFile();
        if (!gui().mayEdit(player) || !gui().exists(file)) {
            player.closeInventory();
            return;
        }

        switch (holder.getMode()) {
            case MAIN -> handleMain(player, file, raw, e.isRightClick());
            case LYRICS -> handleLyrics(player, holder, raw, e.isRightClick(), e.isShiftClick());
        }
    }

    private void handleMain(Player player, String file, int raw, boolean rightClick) {
        LocalTrackSettings settings = gui().settings(file);
        LocalTrackSettings updated = switch (raw) {
            case LocalConfigGuiManager.SLOT_NAME -> {
                gui().prompt(player, new Pending(file, Kind.NAME, 0, false));
                yield null;
            }
            case LocalConfigGuiManager.SLOT_PERMISSION -> {
                gui().prompt(player, new Pending(file, Kind.PERMISSION, 0, false));
                yield null;
            }
            case LocalConfigGuiManager.SLOT_LYRICS -> {
                gui().openLyrics(player, file, 0, false);
                yield null;
            }
            case LocalConfigGuiManager.SLOT_NAME_RESET ->
                    settings.name() == null ? null : settings.withName(null);
            case LocalConfigGuiManager.SLOT_VOLUME_RESET ->
                    settings.volume() == null ? null : settings.withVolume(null);
            case LocalConfigGuiManager.SLOT_PERMISSION_RESET ->
                    settings.permissions().isEmpty() ? null : settings.withPermissions(List.of());
            case LocalConfigGuiManager.SLOT_LYRICS_TOGGLE ->
                    settings.withLyricsEnabled(!settings.lyricsEnabled());
            case LocalConfigGuiManager.SLOT_VOLUME -> settings.withVolume(stepVolume(
                    gui().currentVolume(settings), rightClick ? -1 : 1));
            case LocalConfigGuiManager.SLOT_META_TITLE -> metaClick(player, file, settings, Kind.TITLE, rightClick);
            case LocalConfigGuiManager.SLOT_META_AUTHOR -> metaClick(player, file, settings, Kind.AUTHOR, rightClick);
            case LocalConfigGuiManager.SLOT_META_TEXT -> metaClick(player, file, settings, Kind.TEXT, rightClick);
            case LocalConfigGuiManager.SLOT_ONE_LINE -> {
                LocalTrackSettings.Meta meta = settings.meta();
                yield settings.withMeta(new LocalTrackSettings.Meta(
                        meta.title(), meta.author(), meta.text(), !meta.oneLine()));
            }
            default -> null;
        };
        if (updated == null) return;

        if (gui().save(player, file, updated)) saved(player);
        gui().open(player, file);
    }

    private LocalTrackSettings metaClick(Player player, String file, LocalTrackSettings settings,
                                         Kind kind, boolean reset) {
        if (!reset) {
            gui().prompt(player, new Pending(file, kind, 0, false));
            return null;
        }
        LocalTrackSettings updated = withMeta(settings, kind, null);
        return updated.meta().equals(settings.meta()) ? null : updated;
    }

    private static LocalTrackSettings withMeta(LocalTrackSettings settings, Kind kind, String value) {
        LocalTrackSettings.Meta meta = settings.meta();
        return settings.withMeta(switch (kind) {
            case TITLE -> new LocalTrackSettings.Meta(value, meta.author(), meta.text(), meta.oneLine());
            case AUTHOR -> new LocalTrackSettings.Meta(meta.title(), value, meta.text(), meta.oneLine());
            default -> new LocalTrackSettings.Meta(meta.title(), meta.author(), value, meta.oneLine());
        });
    }

    private static int stepVolume(int current, int direction) {
        int step = LocalConfigGuiManager.VOLUME_STEP;
        int rounded = Math.round(current / (float) step) * step;
        return Math.max(step, Math.min(LocalTrackSettings.MAX_VOLUME, rounded + direction * step));
    }

    private void handleLyrics(Player player, LocalConfigGuiHolder holder, int raw,
                              boolean rightClick, boolean shiftClick) {
        String file = holder.getFile();
        int page = holder.getPage();
        LocalTrackSettings settings = gui().settings(file);

        switch (raw) {
            case LocalConfigGuiManager.SLOT_PREVIOUS -> gui().openLyrics(player, file, page - 1, false);
            case LocalConfigGuiManager.SLOT_NEXT -> gui().openLyrics(player, file, page + 1, false);
            case LocalConfigGuiManager.SLOT_DONE -> gui().open(player, file);
            case LocalConfigGuiManager.SLOT_SYNC -> {
                if (gui().save(player, file, settings.withSyncWithTime(!settings.syncWithTime()))) {
                    saved(player);
                }
                gui().openLyrics(player, file, page, false);
            }
            case LocalConfigGuiManager.SLOT_CLEAR -> {
                if (!holder.isClearArmed()) {
                    gui().openLyrics(player, file, page, !settings.lines().isEmpty());
                    return;
                }
                if (gui().save(player, file, settings.withLines(List.of()))) {
                    Chat.actionBar(player, "§a" + gui().msg(player, "local_config.cleared"));
                }
                gui().openLyrics(player, file, 0, false);
            }
            default -> {
                int grid = LocalConfigGuiManager.gridIndex(raw);
                if (grid < 0) return;

                int index = page * LocalConfigGuiManager.PER_PAGE + grid;
                int count = settings.lines().size();
                if (index > count) return;

                if (index == count) {
                    gui().prompt(player, new Pending(file, Kind.LINE, index, true));
                } else if (shiftClick) {
                    List<LocalTrackSettings.Line> lines = new ArrayList<>(settings.lines());
                    lines.remove(index);
                    if (gui().save(player, file, settings.withLines(lines))) saved(player);
                    gui().openLyrics(player, file, page, false);
                } else {
                    gui().prompt(player, new Pending(file, Kind.LINE, index, rightClick));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        Pending ask = gui().pendingOf(player);
        if (ask == null) return;

        e.setCancelled(true);
        gui().cancel(player);

        String typed = e.getMessage().trim();
        // Chat events arrive asynchronously; the file and the inventory belong to the player's thread.
        Tasks.entity(plugin, player, () -> answer(player, ask, typed));
    }

    private void answer(Player player, Pending ask, String typed) {
        if (!player.isOnline() || !gui().mayEdit(player)) return;
        if (!gui().exists(ask.file())) {
            player.sendMessage("§c" + gui().msg(player, "local_config.not_found", ask.file()));
            return;
        }

        if (!typed.equals(".")) {
            LocalTrackSettings settings = gui().settings(ask.file());
            LocalTrackSettings updated = switch (ask.kind()) {
                case NAME -> named(player, ask.file(), settings, typed);
                case PERMISSION -> {
                    List<String> permissions = LocalTrackSettings.splitPermissions(typed);
                    yield permissions.isEmpty() ? null : settings.withPermissions(permissions);
                }
                case LINE -> lined(settings, ask, typed);
                case TITLE, AUTHOR, TEXT -> {
                    String value = ChatColor.stripColor(typed).trim();
                    if (value.isEmpty() || value.length() > LocalConfigGuiManager.MAX_META_LENGTH) {
                        player.sendMessage("§c" + gui().msg(player, "local_config.meta_invalid",
                                String.valueOf(LocalConfigGuiManager.MAX_META_LENGTH)));
                        yield null;
                    }
                    yield withMeta(settings, ask.kind(), value);
                }
            };
            if (updated != null && gui().save(player, ask.file(), updated)) saved(player);
        }

        if (ask.kind() == Kind.LINE) {
            gui().openLyrics(player, ask.file(), gui().pageOf(ask.index()), false);
        } else {
            gui().open(player, ask.file());
        }
    }

    private LocalTrackSettings named(Player player, String file, LocalTrackSettings settings, String typed) {
        String name = ChatColor.stripColor(typed).trim();
        if (name.isEmpty() || name.length() > LocalConfigGuiManager.MAX_NAME_LENGTH) {
            player.sendMessage("§c" + gui().msg(player, "local_config.name_invalid",
                    String.valueOf(LocalConfigGuiManager.MAX_NAME_LENGTH)));
            return null;
        }

        String owner = gui().library().ownerOfName(name);
        if (owner != null && !owner.equals(file)) {
            player.sendMessage("§c" + gui().msg(player, "local_config.name_taken", owner));
            return null;
        }
        return settings.withName(name);
    }

    private static LocalTrackSettings lined(LocalTrackSettings settings, Pending ask, String typed) {
        LocalTrackSettings.Line line = LocalTrackSettings.parseTyped(typed);
        if (line == null) return null;

        List<LocalTrackSettings.Line> lines = new ArrayList<>(settings.lines());
        int index = Math.min(ask.index(), lines.size());
        if (ask.insert() || index == lines.size()) {
            lines.add(index, line);
        } else {
            lines.set(index, line);
        }
        return settings.withLines(lines);
    }

    private void saved(Player player) {
        Chat.actionBar(player, "§a" + gui().msg(player, "local_config.saved"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        gui().cancel(e.getPlayer());
    }
}
