package dev.valkdz.cdisc.gui.dialog;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.permission.Perms;
import dev.valkdz.cdisc.util.ConfigEditor;
import dev.valkdz.cdisc.util.ConfigEditor.Field;
import dev.valkdz.cdisc.util.ConfigEditor.Kind;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class ConfigDialog {

    private static final int PAGE_SIZE = 12;
    private static final int INPUT_WIDTH = 300;
    private static final int TAB_WIDTH = 100;
    private static final long IDLE_MS = Duration.ofMinutes(15).toMillis();

    private static final ClickCallback.Options CLICKS = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMillis(IDLE_MS))
            .build();

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final class Session {
        final UUID owner;
        final Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
        String file = ConfigEditor.FILES.get(0);
        int page;
        int generation;
        boolean revealed;
        long touched = System.currentTimeMillis();

        Session(UUID owner) {
            this.owner = owner;
        }

        int pendingCount() {
            return pending.values().stream().mapToInt(Map::size).sum();
        }
    }

    private ConfigDialog() {
    }

    static boolean permitted(Main plugin, Player player) {
        return player.isOnline()
                && player.hasPermission(Perms.CONFIG)
                && plugin.cdiscConfig().isConfigDialogEnabled()
                && Dialogs.supported();
    }

    static void open(Main plugin, Player player) {
        if (!permitted(plugin, player)) return;
        long now = System.currentTimeMillis();
        SESSIONS.values().removeIf(s -> now - s.touched > IDLE_MS);

        Session session = new Session(player.getUniqueId());
        SESSIONS.put(session.owner, session);
        show(plugin, player, session, List.of());
    }

    private static void show(Main plugin, Player player, Session session, List<Component> notices) {
        List<Field> fields;
        try {
            fields = ConfigEditor.fields(plugin, session.file);
        } catch (Exception e) {
            plugin.getLogger().warning("Settings window could not read " + session.file + ": " + e);
            fields = List.of();
        }

        int pages = Math.max(1, (fields.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        session.page = Math.max(0, Math.min(session.page, pages - 1));
        List<Field> shown = fields.subList(session.page * PAGE_SIZE,
                Math.min(fields.size(), (session.page + 1) * PAGE_SIZE));
        int generation = ++session.generation;

        Map<String, Object> pending = session.pending.getOrDefault(session.file, Map.of());
        List<DialogInput> inputs = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            inputs.add(input(plugin, player, "f" + i, shown.get(i), pending.get(shown.get(i).path()),
                    session.revealed));
        }

        List<DialogBody> body = new ArrayList<>();
        for (Component notice : notices) body.add(DialogBody.plainMessage(notice));
        body.add(DialogBody.plainMessage(text(plugin, player, "config_dialog.hint")));
        body.add(DialogBody.plainMessage(text(plugin, player, "config_dialog.page",
                session.page + 1, pages)));
        if (session.pendingCount() > 0) {
            body.add(DialogBody.plainMessage(text(plugin, player, "config_dialog.pending",
                    session.pendingCount())));
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (String file : ConfigEditor.FILES) {
            String color = file.equals(session.file) ? "§a§n" : "§7";
            buttons.add(button(plugin, session, generation, shown, PlayerDialog.legacy(color + file),
                    TAB_WIDTH, (p, s) -> {
                        s.file = file;
                        s.page = 0;
                    }));
        }
        if (session.page > 0) {
            buttons.add(button(plugin, session, generation, shown,
                    text(plugin, player, "config_dialog.prev"), TAB_WIDTH, (p, s) -> s.page--));
        }
        buttons.add(saveButton(plugin, session, generation, shown, text(plugin, player, "config_dialog.save")));
        if (fields.stream().anyMatch(f -> f.kind() == Kind.SECRET)) {
            String toggle = session.revealed ? "config_dialog.conceal" : "config_dialog.reveal";
            buttons.add(button(plugin, session, generation, shown, text(plugin, player, toggle),
                    TAB_WIDTH, (p, s) -> {
                        s.revealed = !s.revealed;
                        if (s.revealed) {
                            plugin.getLogger().info(p.getName() + " (" + p.getUniqueId()
                                    + ") revealed the secrets of " + s.file + " in the settings window.");
                        }
                    }));
        }
        if (session.page < pages - 1) {
            buttons.add(button(plugin, session, generation, shown,
                    text(plugin, player, "config_dialog.next"), TAB_WIDTH, (p, s) -> s.page++));
        }

        ActionButton close = ActionButton.create(text(plugin, player, "config_dialog.close"), null,
                TAB_WIDTH, DialogAction.customClick((view, audience) ->
                        SESSIONS.remove(session.owner, session), CLICKS));

        DialogBase base = DialogBase.builder(text(plugin, player, "config_dialog.title", session.file))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(inputs)
                .build();

        PlayerDialog.audience(player).showDialog(Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(buttons).exitAction(close).columns(4).build())));
    }

    private static DialogInput input(Main plugin, Player player, String key, Field field, Object pending,
                                     boolean revealed) {
        Component label = PlayerDialog.legacy("§f" + field.label());

        if (field.kind() == Kind.BOOL) {
            boolean initial = pending instanceof Boolean b ? b : Boolean.TRUE.equals(field.value());
            return DialogInput.bool(key, label).initial(initial).build();
        }

        String initial;
        if (field.kind() == Kind.SECRET && revealed) {
            label = text(plugin, player, "config_dialog.secret_shown", field.label());
            initial = pending != null ? displayOf(pending) : field.display();
        } else if (field.kind() == Kind.SECRET) {
            boolean set = field.value() != null && !field.display().isEmpty();
            label = text(plugin, player, set ? "config_dialog.secret_set" : "config_dialog.secret_empty",
                    field.label());
            initial = "";
        } else {
            initial = pending != null ? displayOf(pending) : field.display();
        }
        return DialogInput.text(key, label)
                .initial(initial.length() > ConfigEditor.MAX_LENGTH
                        ? initial.substring(0, ConfigEditor.MAX_LENGTH) : initial)
                .maxLength(ConfigEditor.MAX_LENGTH)
                .width(INPUT_WIDTH)
                .build();
    }

    private static String displayOf(Object value) {
        return value instanceof List<?> list
                ? String.join(", ", list.stream().map(String::valueOf).toList())
                : String.valueOf(value);
    }

    private interface Step {
        void run(Player player, Session session);
    }

    private static ActionButton button(Main plugin, Session session, int generation, List<Field> shown,
                                       Component label, int width, Step step) {
        return ActionButton.create(label, null, width, DialogAction.customClick((view, audience) ->
                handle(plugin, session, generation, audience, player -> {
                    List<Component> errors = stash(plugin, player, session, shown, view);
                    if (errors.isEmpty()) step.run(player, session);
                    show(plugin, player, session, errors);
                }), CLICKS));
    }

    private static ActionButton saveButton(Main plugin, Session session, int generation,
                                           List<Field> shown, Component label) {
        return ActionButton.create(label, null, TAB_WIDTH, DialogAction.customClick((view, audience) ->
                handle(plugin, session, generation, audience, player -> {
                    List<Component> errors = stash(plugin, player, session, shown, view);
                    show(plugin, player, session, errors.isEmpty() ? save(plugin, player, session) : errors);
                }), CLICKS));
    }

    // Every click is re-checked on the main thread: the window may outlive the right to use it.
    private static void handle(Main plugin, Session session, int generation, Audience audience,
                               Consumer<Player> action) {
        PlayerDialog.onMainThread(plugin, () -> {
            if (!(audience instanceof Player player) || !player.getUniqueId().equals(session.owner)) {
                return;
            }

            boolean current = SESSIONS.get(session.owner) == session && session.generation == generation;
            if (!current || System.currentTimeMillis() - session.touched > IDLE_MS) {
                SESSIONS.remove(session.owner, session);
                PlayerDialog.audience(player).sendMessage(text(plugin, player, "config_dialog.expired"));
                return;
            }
            if (!permitted(plugin, player)) {
                SESSIONS.remove(session.owner, session);
                PlayerDialog.audience(player).closeDialog();
                plugin.getLogger().warning(player.getName() + " used the settings window without "
                        + Perms.CONFIG + " or while it is switched off; nothing was changed.");
                PlayerDialog.audience(player).sendMessage(text(plugin, player, "perms.denied", Perms.CONFIG));
                return;
            }

            session.touched = System.currentTimeMillis();
            action.accept(player);
        });
    }

    private static List<Component> stash(Main plugin, Player player, Session session,
                                         List<Field> shown, DialogResponseView view) {
        Map<String, Object> pending = session.pending.computeIfAbsent(session.file, f -> new LinkedHashMap<>());
        List<Component> errors = new ArrayList<>();

        for (int i = 0; i < shown.size(); i++) {
            Field field = shown.get(i);
            String key = "f" + i;

            if (field.kind() == Kind.BOOL) {
                Boolean value = view.getBoolean(key);
                if (value == null) continue;
                if (value.equals(field.value())) pending.remove(field.path());
                else pending.put(field.path(), value);
                continue;
            }

            String raw = view.getText(key);
            boolean masked = field.kind() == Kind.SECRET && !session.revealed;
            if (raw == null || (masked && raw.isEmpty())) continue;
            if (!masked && raw.trim().equals(field.display())) {
                pending.remove(field.path());
                continue;
            }

            try {
                pending.put(field.path(), ConfigEditor.parse(field.kind(), raw));
            } catch (IllegalArgumentException e) {
                errors.add(text(plugin, player, "config_dialog.bad_value", field.label(),
                        plugin.getMessageManager().get(player, "config_dialog.bad_" + e.getMessage())));
            }
        }

        if (pending.isEmpty()) session.pending.remove(session.file);
        return errors;
    }

    private static List<Component> save(Main plugin, Player player, Session session) {
        if (session.pendingCount() == 0) {
            return List.of(text(plugin, player, "config_dialog.nothing"));
        }

        int written = 0;
        for (Map.Entry<String, Map<String, Object>> entry : new ArrayList<>(session.pending.entrySet())) {
            String file = entry.getKey();
            Map<String, Object> changes = entry.getValue();
            Map<String, Kind> kinds = kindsOf(plugin, file);
            try {
                ConfigEditor.write(plugin, file, changes);
            } catch (Exception e) {
                plugin.getLogger().warning(player.getName() + " could not save " + file
                        + " from the settings window: " + e.getMessage());
                if (written > 0) plugin.reloadEverything();
                return List.of(text(plugin, player, "config_dialog.failed", file, String.valueOf(e.getMessage())));
            }

            for (Map.Entry<String, Object> change : changes.entrySet()) {
                boolean secret = !kinds.containsKey(change.getKey()) || kinds.get(change.getKey()) == Kind.SECRET;
                plugin.getLogger().info(player.getName() + " (" + player.getUniqueId() + ") set "
                        + file + " " + ConfigEditor.labelOf(change.getKey()) + " to "
                        + (secret ? "<hidden>" : displayOf(change.getValue())) + " in the settings window.");
            }
            written += changes.size();
            session.pending.remove(file);
        }

        plugin.reloadEverything();
        return List.of(text(plugin, player, "config_dialog.saved", written));
    }

    private static Map<String, Kind> kindsOf(Main plugin, String file) {
        Map<String, Kind> kinds = new LinkedHashMap<>();
        try {
            for (Field field : ConfigEditor.fields(plugin, file)) kinds.put(field.path(), field.kind());
        } catch (Exception ignored) {
        }
        return kinds;
    }

    private static Component text(Main plugin, Player player, String key, Object... args) {
        return PlayerDialog.legacy(plugin.getMessageManager().get(player, key, args));
    }
}
