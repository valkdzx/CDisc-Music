package dev.valkdz.cdisc.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageKeysTest {

    private static final Path LANGUAGES = Path.of("src", "main", "resources", "languages");
    private static final Path SOURCES = Path.of("src", "main", "java");

    private static final Pattern LOOKUP =
            Pattern.compile("\\.get\\(\\s*\\w+\\s*,\\s*\"([a-zA-Z0-9_.]+)\"");

    private static final Pattern SPECIFIER =
            Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]");

    @Test
    @DisplayName("no key parses as something other than a string")
    void noKeyParsesAsABoolean() throws IOException {

        for (Path file : languageFiles()) {
            Map<String, Object> flat = flatten(load(file));

            List<String> odd = new ArrayList<>();
            collectNonStringKeys(load(file), "", odd);

            assertTrue(odd.isEmpty(),
                    file.getFileName() + " has keys YAML did not read as text: " + odd
                            + " — quote them or rename them (on -> state_on)");
            assertTrue(!flat.isEmpty(), file + " parsed empty");
        }
    }

    @Test
    @DisplayName("every language carries exactly the keys en_US does")
    void languagesAgree() throws IOException {
        Set<String> english = new TreeSet<>(flatten(load(english())).keySet());
        assertTrue(english.size() > 300, "en_US parsed thin: " + english.size());

        for (Path file : languageFiles()) {
            if (file.equals(english())) continue;
            Set<String> theirs = new TreeSet<>(flatten(load(file)).keySet());

            Set<String> missing = new TreeSet<>(english);
            missing.removeAll(theirs);
            Set<String> extra = new TreeSet<>(theirs);
            extra.removeAll(english);

            assertTrue(missing.isEmpty(), file.getFileName() + " is missing: " + missing);
            assertTrue(extra.isEmpty(), file.getFileName() + " has keys en_US hasn't: " + extra);
        }
    }

    @Test
    @DisplayName("every translation keeps the same format specifiers, in the same order")
    void formatSpecifiersAgree() throws IOException {
        Map<String, Object> english = flatten(load(english()));

        for (Path file : languageFiles()) {
            if (file.equals(english())) continue;
            Map<String, Object> theirs = flatten(load(file));

            List<String> wrong = new ArrayList<>();
            for (Map.Entry<String, Object> entry : english.entrySet()) {
                Object mine = theirs.get(entry.getKey());
                if (mine == null) continue;

                List<String> want = specifiers(String.valueOf(entry.getValue()));
                List<String> got = specifiers(String.valueOf(mine));
                if (!want.equals(got)) {
                    wrong.add(entry.getKey() + " expects " + want + " but has " + got);
                }
            }

            // String.format takes them positionally, so a reordered or dropped
            // specifier is a crash or a wrong number at runtime, not a typo.
            assertTrue(wrong.isEmpty(), file.getFileName() + ": " + wrong);
        }
    }

    private static List<String> specifiers(String message) {
        List<String> found = new ArrayList<>();
        Matcher matcher = SPECIFIER.matcher(message);
        while (matcher.find()) {
            if (!matcher.group().equals("%%")) found.add(matcher.group());
        }
        return found;
    }

    private static Path english() {
        return LANGUAGES.resolve("en_US.yml");
    }

    @Test
    @DisplayName("every key the code asks for by name exists in both languages")
    void everyLookupResolves() throws IOException {
        Set<String> asked = lookupsInSources();
        assertTrue(asked.size() > 50, "expected to find the message lookups, found " + asked.size());

        for (Path file : languageFiles()) {
            Set<String> have = flatten(load(file)).keySet();

            Set<String> missing = new TreeSet<>();
            for (String key : asked) {
                if (!have.contains(key)) missing.add(key);
            }

            assertTrue(missing.isEmpty(), file.getFileName() + " is missing: " + missing);
        }
    }

    private static List<Path> languageFiles() throws IOException {
        try (Stream<Path> files = Files.list(LANGUAGES)) {
            return files.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            return loaded instanceof Map ? (Map<Object, Object>) loaded : Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<Object, Object> tree) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : tree.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (entry.getValue() instanceof Map) {
                for (Map.Entry<String, Object> nested
                        : flatten((Map<Object, Object>) entry.getValue()).entrySet()) {
                    flat.put(key + "." + nested.getKey(), nested.getValue());
                }
            } else {
                flat.put(key, entry.getValue());
            }
        }
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void collectNonStringKeys(Map<Object, Object> tree, String path,
                                             List<String> found) {
        for (Map.Entry<Object, Object> entry : tree.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                found.add(path + entry.getKey() + " (" + entry.getKey().getClass().getSimpleName() + ")");
            }
            if (entry.getValue() instanceof Map) {
                collectNonStringKeys((Map<Object, Object>) entry.getValue(),
                        path + entry.getKey() + ".", found);
            }
        }
    }

    private static Set<String> lookupsInSources() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String source : javaSources()) {
            Matcher matcher = LOOKUP.matcher(source);
            while (matcher.find()) {
                String key = matcher.group(1);

                if (!isPrefix(key) && key.contains(".")) keys.add(key);
            }
        }
        return keys;
    }

    private static boolean isPrefix(String literal) {
        return literal.endsWith("_") || literal.endsWith(".");
    }

    private static List<String> javaSources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            List<Path> java = files.filter(p -> p.toString().endsWith(".java")).toList();

            List<String> read = new ArrayList<>(java.size());
            for (Path file : java) {
                read.add(Files.readString(file, StandardCharsets.UTF_8));
            }
            return read;
        }
    }
}
