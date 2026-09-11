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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDefaultsTest {

    private static final Map<String, String> PAIRS = Map.of(
            "src/main/java/dev/valkdz/cdisc/util/Config.java",
            "src/main/resources/config.yml",

            "src/main/java/dev/valkdz/cdisc/util/SourcesConfig.java",
            "src/main/resources/sources.yml");

    private static final Pattern WITH_DEFAULT = Pattern.compile(
            "get(String|Int|Boolean|Double|Long)\\(\\s*\"([a-zA-Z0-9_.-]+)\"\\s*,\\s*"
                    + "(\"[^\"]*\"|true|false|-?\\d+(?:\\.\\d+)?)[LfFdD]?\\s*\\)");

    @Test
    @DisplayName("every fallback in the code matches the value shipped in the file")
    void fallbacksMatchTheBundledFiles() throws IOException {
        List<String> disagreements = new ArrayList<>();

        for (Map.Entry<String, String> pair : PAIRS.entrySet()) {
            String java = Files.readString(Path.of(pair.getKey()), StandardCharsets.UTF_8);
            Map<String, Object> shipped = flatten(load(Path.of(pair.getValue())));

            Matcher matcher = WITH_DEFAULT.matcher(java);
            while (matcher.find()) {
                String path = matcher.group(2);
                String written = matcher.group(3);

                if (!shipped.containsKey(path)) continue;

                String inFile = String.valueOf(shipped.get(path));
                String inCode = written.startsWith("\"")
                        ? written.substring(1, written.length() - 1)
                        : written;

                if (!sameValue(inFile, inCode)) {
                    disagreements.add(pair.getValue() + " has " + path + " = " + inFile
                            + " but " + Path.of(pair.getKey()).getFileName()
                            + " falls back to " + inCode);
                }
            }
        }

        assertTrue(disagreements.isEmpty(),
                "a server whose config predates one of these keys gets the code's answer, "
                        + "not the file's:\n  " + String.join("\n  ", disagreements));
    }

    @Test
    @DisplayName("the scan actually finds the accessors it is meant to check")
    void theScanIsNotVacuous() throws IOException {
        int found = 0;
        for (String file : PAIRS.keySet()) {
            Matcher matcher = WITH_DEFAULT.matcher(
                    Files.readString(Path.of(file), StandardCharsets.UTF_8));
            while (matcher.find()) found++;
        }

        assertTrue(found > 30, "expected to find the accessors, found " + found);
    }

    private static boolean sameValue(String inFile, String inCode) {
        if (inFile.equals(inCode)) return true;
        try {
            return Double.compare(Double.parseDouble(inFile), Double.parseDouble(inCode)) == 0;
        } catch (NumberFormatException e) {
            return false;
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
}
