package dev.valkdz.cdisc.util;

import dev.valkdz.cdisc.Main;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ConfigEditor {

    public static final List<String> FILES = List.of(
            "config.yml", "sources.yml", "permissions.yml", "tokens.yml");

    public static final int MAX_LENGTH = 1024;

    public enum Kind { BOOL, INTEGER, DECIMAL, TEXT, LIST, RULE, SECRET }

    public record Field(String path, Kind kind, Object value) {

        public String label() {
            return labelOf(path);
        }

        public String display() {
            return ConfigEditor.display(value);
        }
    }

    private static final Object LOCK = new Object();

    public static String labelOf(String path) {
        return path.replace(ConfigUpgrade.SEP, '.');
    }

    private ConfigEditor() {
    }

    public static List<Field> fields(Main plugin, String name) throws IOException {
        requireEditable(name);
        String bundled = ConfigUpgrade.readResource(plugin, name);
        if (bundled == null) throw new IOException(name + " is not in the jar");
        return fields(name, bundled, Files.readString(fileOf(plugin, name).toPath(), StandardCharsets.UTF_8));
    }

    static List<Field> fields(String name, String bundledText, String currentText) {
        YamlConfiguration bundled = ConfigUpgrade.load(bundledText);
        YamlConfiguration user = ConfigUpgrade.load(currentText);

        List<Field> out = new ArrayList<>();
        for (String path : bundled.getKeys(true)) {
            Object shipped = bundled.get(path);
            if (shipped instanceof ConfigurationSection) continue;

            Kind kind = kindOf(name, path, shipped);
            if (kind == null) continue;

            Object value = user.contains(path) ? ConfigUpgrade.plain(user.get(path)) : shipped;
            if (kind == Kind.BOOL && !(value instanceof Boolean)) value = shipped;
            out.add(new Field(path, kind, value));
        }
        return out;
    }

    private static Kind kindOf(String name, String path, Object shipped) {
        if (name.equals("permissions.yml") && path.startsWith("actions" + ConfigUpgrade.SEP)) {
            return Kind.RULE;
        }
        if (shipped instanceof Boolean) return Kind.BOOL;
        if (shipped instanceof Integer || shipped instanceof Long) return Kind.INTEGER;
        if (shipped instanceof Double || shipped instanceof Float) return Kind.DECIMAL;
        if (shipped instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map || item instanceof List) return null;
            }
            return Kind.LIST;
        }
        if (shipped instanceof String) {
            boolean secret = name.equals("tokens.yml")
                    && !path.toLowerCase(Locale.ROOT).endsWith("url");
            return secret ? Kind.SECRET : Kind.TEXT;
        }
        return null;
    }

    static String display(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) parts.add(String.valueOf(item));
            return String.join(", ", parts);
        }
        return String.valueOf(value);
    }

    public static Object parse(Kind kind, String raw) {
        if (raw == null) throw new IllegalArgumentException("text");
        if (raw.length() > MAX_LENGTH) throw new IllegalArgumentException("text");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) throw new IllegalArgumentException("text");
        }

        String trimmed = raw.trim();
        switch (kind) {
            case INTEGER -> {
                try {
                    long number = Long.parseLong(trimmed);
                    return number == (int) number ? (Object) (int) number : (Object) number;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("integer");
                }
            }
            case DECIMAL -> {
                try {
                    double number = Double.parseDouble(trimmed);
                    if (Double.isNaN(number) || Double.isInfinite(number)) throw new NumberFormatException();
                    return number;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("decimal");
                }
            }
            case LIST -> {
                return split(trimmed);
            }
            case RULE -> {
                if (trimmed.equalsIgnoreCase("true")) return true;
                if (trimmed.equalsIgnoreCase("false")) return false;
                String bare = trimmed.startsWith("[") && trimmed.endsWith("]")
                        ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
                List<String> rules = split(bare);
                if (rules.isEmpty()) throw new IllegalArgumentException("text");
                return rules.size() == 1 && !trimmed.startsWith("[") ? rules.get(0) : rules;
            }
            case BOOL -> throw new IllegalArgumentException("text");
            default -> {
                return raw;
            }
        }
    }

    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split(",")) {
            String clean = part.trim();
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    public static void write(Main plugin, String name, Map<String, Object> changes) throws IOException {
        requireEditable(name);
        String bundled = ConfigUpgrade.readResource(plugin, name);
        if (bundled == null) throw new IOException(name + " is not in the jar");

        synchronized (LOCK) {
            File file = fileOf(plugin, name);
            String current = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String updated = apply(name, bundled, current, changes);
            if (updated.equals(current)) return;

            ConfigUpgrade.backUp(file, plugin.getLogger());
            Path temp = file.toPath().resolveSibling(file.getName() + ".tmp");
            Files.writeString(temp, updated, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // Only paths the template ships may be written, and each must read back exactly as sent.
    static String apply(String name, String bundledText, String currentText, Map<String, Object> changes)
            throws IOException {
        List<Field> known = fields(name, bundledText, currentText);
        YamlConfiguration user = ConfigUpgrade.load(currentText);

        for (Map.Entry<String, Object> change : changes.entrySet()) {
            boolean shipped = known.stream().anyMatch(f -> f.path().equals(change.getKey()));
            if (!shipped) throw new IOException("unknown setting " + change.getKey());
            user.set(change.getKey(), change.getValue());
        }

        ConfigUpgrade.Outcome outcome = ConfigUpgrade.merge(bundledText, user.saveToString());
        if (!outcome.trustworthy) throw new IOException("the result did not read back as written");

        YamlConfiguration result = ConfigUpgrade.load(outcome.text);
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            Object got = ConfigUpgrade.plain(result.get(change.getKey()));
            if (!Objects.equals(ConfigUpgrade.plain(change.getValue()), got)) {
                throw new IOException(change.getKey().replace(ConfigUpgrade.SEP, '.')
                        + " did not read back as written");
            }
        }
        return outcome.text;
    }

    private static void requireEditable(String name) {
        if (!FILES.contains(name)) throw new IllegalArgumentException("not editable: " + name);
    }

    private static File fileOf(Main plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        return file;
    }
}
