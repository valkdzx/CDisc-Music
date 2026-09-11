package dev.valkdz.cdisc.util;

import dev.valkdz.cdisc.Main;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Rewrites each settings file from the copy bundled in the jar. New keys and
// reworded comments arrive; anything the admin changed by hand stays changed.
public final class ConfigUpgrade {

    // Literal keys: permissions.yml holds nodes like cdisc.create, which Bukkit's
    // own '.' separator would read as three nested sections.
    private static final char SEP = '\u0000';

    private static final Pattern KEY = Pattern.compile("^(\\s*)([^#:][^:]*):(?:\\s+(.*?))?\\s*$");

    public static final List<String> FILES = List.of(
            "config.yml",
            "sources.yml",
            "tokens.yml",
            "permissions.yml",
            "languages/ar_SA.yml",
            "languages/de_DE.yml",
            "languages/en_US.yml",
            "languages/es_ES.yml",
            "languages/he_IL.yml",
            "languages/ru_RU.yml",
            "languages/uk_UA.yml");

    private ConfigUpgrade() {
    }

    public static void runAll(Main plugin) {
        for (String name : FILES) {
            try {
                run(plugin, name);
            } catch (Exception e) {
                plugin.getLogger().warning("Couldn't bring " + name + " up to date: "
                        + e + ". The file was left exactly as it was.");
            }
        }
    }

    private static void run(Main plugin, String name) throws IOException {
        String bundledText = readResource(plugin, name);
        if (bundledText == null) return;

        File file = new File(plugin.getDataFolder(), name.replace('/', File.separatorChar));
        if (!file.exists()) {
            plugin.saveResource(name, false);
            return;
        }

        String currentText = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        Outcome outcome = merge(bundledText, currentText);
        if (outcome.text.equals(currentText)) return;

        if (!outcome.trustworthy) {
            plugin.getLogger().warning("The rewritten " + name + " did not read back as the"
                    + " settings it was built from, so it was thrown away and your file left"
                    + " alone. Please report this.");
            return;
        }

        backUp(file, plugin.getLogger());
        Files.writeString(file.toPath(), outcome.text, StandardCharsets.UTF_8);
        report(plugin.getLogger(), name, outcome);
    }

    // Pure, so the tests can exercise it without a server.
    public static Outcome merge(String bundledText, String currentText) {
        YamlConfiguration user = load(currentText);
        YamlConfiguration bundled = load(bundledText);

        Outcome outcome = new Outcome();
        List<String> lines = new ArrayList<>(Arrays.asList(bundledText.split("\\r?\\n", -1)));
        List<String> merged = new ArrayList<>();
        mergeSection(lines, new int[]{0}, 0, user, bundled, merged, outcome, "");

        // Whatever the bundled copy uses. Otherwise every line of a CRLF file reads
        // as changed, and the upgrade rewrites it on every startup for nothing.
        String eol = bundledText.contains("\r\n") ? "\r\n" : "\n";
        outcome.text = String.join(eol, trimTrailingBlanks(merged)) + eol;
        outcome.trustworthy = survivesRoundTrip(outcome.text, user, bundled);
        return outcome;
    }

    private static void mergeSection(List<String> lines, int[] cursor, int indent,
                                     ConfigurationSection user, ConfigurationSection bundled,
                                     List<String> out, Outcome outcome, String prefix) {
        List<String> seen = new ArrayList<>();

        while (cursor[0] < lines.size()) {
            String line = lines.get(cursor[0]);
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.add(line);
                cursor[0]++;
                continue;
            }

            int lineIndent = indentOf(line);
            if (lineIndent < indent) break;

            Matcher m = KEY.matcher(line);
            if (!m.matches()) {
                out.add(line);
                cursor[0]++;
                continue;
            }

            String key = m.group(2).trim();
            String inline = m.group(3) == null ? "" : m.group(3).trim();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            seen.add(key);

            int nextIndent = -1;
            boolean nextIsItem = false;
            for (int i = cursor[0] + 1; i < lines.size(); i++) {
                String ahead = lines.get(i).trim();
                if (ahead.isEmpty() || ahead.startsWith("#")) continue;
                nextIndent = indentOf(lines.get(i));
                nextIsItem = ahead.startsWith("- ") || ahead.equals("-");
                break;
            }

            boolean isSection = inline.isEmpty() && nextIndent > lineIndent && !nextIsItem;

            if (isSection) {
                out.add(line);
                cursor[0]++;
                mergeSection(lines, cursor, nextIndent,
                        child(user, key), child(bundled, key), out, outcome, path);
                continue;
            }

            boolean held = user != null && user.getKeys(false).contains(key);
            Object mine = held ? plain(user.get(key)) : null;
            Object theirs = bundled == null ? null : plain(bundled.get(key));

            if (!held) {
                outcome.added.add(path);
            }

            List<String> shipped = valueLines(lines, cursor[0], lineIndent);

            if (held && !Objects.equals(mine, theirs)) {
                out.addAll(render(key, mine, m.group(1)));
                outcome.kept++;
            } else {
                out.add(line);
                out.addAll(shipped);
            }

            cursor[0] += 1 + shipped.size();
        }

        if (user != null) {
            for (String key : user.getKeys(false)) {
                if (!seen.contains(key)) {
                    outcome.dropped.add(prefix.isEmpty() ? key : prefix + "." + key);
                }
            }
        }
    }

    private static List<String> valueLines(List<String> lines, int keyLine, int keyIndent) {
        List<String> owned = new ArrayList<>();
        for (int i = keyLine + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) break;
            int ind = indentOf(raw);
            boolean item = trimmed.startsWith("- ") || trimmed.equals("-");
            if (item && ind >= keyIndent) {
                owned.add(raw);
                continue;
            }
            if (ind > keyIndent) {
                owned.add(raw);
                continue;
            }
            break;
        }
        return owned;
    }

    private static List<String> render(String key, Object value, String indent) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.options().pathSeparator(SEP);
        tmp.set(key, value);

        List<String> out = new ArrayList<>();
        for (String line : tmp.saveToString().split("\\r?\\n", -1)) {
            if (line.isEmpty()) continue;
            // Bukkit dumps a sequence flush with its key; nest it to match the template.
            String nested = line.trim().startsWith("- ") ? "  " + line : line;
            out.add(indent + nested);
        }
        return out;
    }

    // Anything the merge lost or altered is a bug in here, and the admin's file is
    // left alone rather than overwritten with it.
    private static boolean survivesRoundTrip(String mergedText, ConfigurationSection user,
                                             ConfigurationSection bundled) {
        YamlConfiguration result;
        try {
            result = load(mergedText);
        } catch (Exception e) {
            return false;
        }
        return matches(result, user, bundled);
    }

    private static boolean matches(ConfigurationSection result, ConfigurationSection user,
                                   ConfigurationSection bundled) {
        if (bundled == null) return true;

        for (String key : bundled.getKeys(false)) {
            Object shipped = bundled.get(key);
            boolean held = user != null && user.getKeys(false).contains(key);

            if (shipped instanceof ConfigurationSection section && !section.getKeys(false).isEmpty()) {
                if (!matches(child(result, key), child(user, key), section)) return false;
                continue;
            }

            Object want = plain(held ? user.get(key) : shipped);
            Object got = result == null ? null : plain(result.get(key));
            if (!Objects.equals(want, got)) return false;
        }
        return true;
    }

    private static void report(Logger log, String name, Outcome outcome) {
        StringBuilder note = new StringBuilder(name + " brought up to date");
        if (!outcome.added.isEmpty()) {
            note.append("; added ").append(outcome.added.size())
                    .append(" new setting(s): ").append(String.join(", ", outcome.added));
        }
        if (!outcome.dropped.isEmpty()) {
            note.append("; dropped ").append(outcome.dropped.size())
                    .append(" this version no longer reads: ")
                    .append(String.join(", ", outcome.dropped));
        }
        if (outcome.kept > 0) {
            note.append("; kept ").append(outcome.kept).append(" of your own value(s)");
        }
        note.append(". Previous file: ").append(backupName(name));
        log.info(note.toString());
    }

    private static void backUp(File file, Logger log) {
        try {
            Path backup = file.toPath().resolveSibling(backupName(file.getName()));
            Files.copy(file.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Couldn't keep a copy of " + file.getName()
                    + " before rewriting it: " + e.getMessage());
        }
    }

    private static String backupName(String name) {
        String bare = name.substring(name.lastIndexOf('/') + 1);
        return bare + ".old";
    }

    private static YamlConfiguration load(String text) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().pathSeparator(SEP);
        try {
            cfg.loadFromString(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("not readable as YAML: " + e.getMessage(), e);
        }
        return cfg;
    }

    private static String readResource(Main plugin, String name) throws IOException {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ConfigurationSection child(ConfigurationSection parent, String key) {
        if (parent == null) return null;
        Object value = parent.get(key);
        return value instanceof ConfigurationSection section ? section : null;
    }

    private static Object plain(Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                map.put(key, plain(section.get(key)));
            }
            return map;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), plain(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(plain(item));
            return copy;
        }
        return value;
    }

    private static List<String> trimTrailingBlanks(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).trim().isEmpty()) end--;
        return lines.subList(0, end);
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    public static final class Outcome {
        public final List<String> added = new ArrayList<>();
        public final List<String> dropped = new ArrayList<>();
        public int kept;

        public String text = "";

        public boolean trustworthy;
    }
}
