package dev.valkdz.cdisc.audio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.valkdz.cdisc.lyrics.SyncedLyrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalTrackSettings {

    public static final String EXTENSION = ".jsonc";

    public static final int MAX_VOLUME = 100;

    private static final long UNTIMED_GAP_MS = 4_000L;

    private static final Pattern TIME =
            Pattern.compile("(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?");

    private static final Pattern TIMED_LINE =
            Pattern.compile("^\\s*\\[(\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?)]\\s?(.*)$", Pattern.DOTALL);

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .build();

    public static final LocalTrackSettings EMPTY =
            new LocalTrackSettings(null, null, List.of(), Meta.NONE, true, true, List.of());

    public record Meta(String title, String author, String text, boolean oneLine) {

        public static final Meta NONE = new Meta(null, null, null, false);

        public Meta {
            title = blankToNull(title);
            author = blankToNull(author);
            text = blankToNull(text);
        }

        public boolean isEmpty() {
            return title == null && author == null && text == null && !oneLine;
        }

        public boolean showsOneLine() {
            return oneLine && text != null;
        }

        public Shown apply(String defaultTitle, String defaultAuthor) {
            if (showsOneLine()) return new Shown(text, "");
            return new Shown(title != null ? title : defaultTitle, author != null ? author : defaultAuthor);
        }
    }

    public record Shown(String title, String author) {
    }

    public record Line(Long timeMs, String text) {

        public boolean timed() {
            return timeMs != null;
        }

        public String asTyped() {
            return timed() ? "[" + formatTime(timeMs) + "] " + text : text;
        }
    }

    private record Timeline(long durationMs, SyncedLyrics lyrics) {
    }

    private final String name;
    private final Integer volume;
    private final List<String> permissions;
    private final Meta meta;
    private final boolean lyricsEnabled;
    private final boolean syncWithTime;
    private final List<Line> lines;

    private volatile Timeline timeline;

    public LocalTrackSettings(String name, Integer volume, List<String> permissions, Meta meta,
                              boolean lyricsEnabled, boolean syncWithTime, List<Line> lines) {
        this.name = blankToNull(name);
        this.volume = volume == null ? null : Math.max(0, Math.min(MAX_VOLUME, volume));
        this.permissions = List.copyOf(permissions);
        this.meta = meta == null ? Meta.NONE : meta;
        this.lyricsEnabled = lyricsEnabled;
        this.syncWithTime = syncWithTime;
        this.lines = List.copyOf(lines);
    }

    public String name() {
        return name;
    }

    public Integer volume() {
        return volume;
    }

    public List<String> permissions() {
        return permissions;
    }

    public Meta meta() {
        return meta;
    }

    public boolean lyricsEnabled() {
        return lyricsEnabled;
    }

    public boolean syncWithTime() {
        return syncWithTime;
    }

    public List<Line> lines() {
        return lines;
    }

    public LocalTrackSettings withName(String value) {
        return new LocalTrackSettings(value, volume, permissions, meta, lyricsEnabled, syncWithTime, lines);
    }

    public LocalTrackSettings withVolume(Integer value) {
        return new LocalTrackSettings(name, value, permissions, meta, lyricsEnabled, syncWithTime, lines);
    }

    public LocalTrackSettings withPermissions(List<String> value) {
        return new LocalTrackSettings(name, volume, value, meta, lyricsEnabled, syncWithTime, lines);
    }

    public LocalTrackSettings withMeta(Meta value) {
        return new LocalTrackSettings(name, volume, permissions, value, lyricsEnabled, syncWithTime, lines);
    }

    public LocalTrackSettings withLyricsEnabled(boolean value) {
        return new LocalTrackSettings(name, volume, permissions, meta, value, syncWithTime, lines);
    }

    public LocalTrackSettings withSyncWithTime(boolean value) {
        return new LocalTrackSettings(name, volume, permissions, meta, lyricsEnabled, value, lines);
    }

    public LocalTrackSettings withLines(List<Line> value) {
        return new LocalTrackSettings(name, volume, permissions, meta, lyricsEnabled, syncWithTime, value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public SyncedLyrics lyrics(long durationMs) {
        if (!lyricsEnabled || lines.isEmpty()) return null;

        Timeline built = timeline;
        if (built != null && built.durationMs() == durationMs) return built.lyrics();

        SyncedLyrics lyrics = SyncedLyrics.of(place(durationMs));
        timeline = new Timeline(durationMs, lyrics);
        return lyrics;
    }

    private List<SyncedLyrics.Line> place(long durationMs) {
        boolean known = durationMs > 0 && durationMs != Long.MAX_VALUE;
        int count = lines.size();
        List<SyncedLyrics.Line> placed = new ArrayList<>(count);

        if (!syncWithTime) {
            long step = known ? durationMs / count : UNTIMED_GAP_MS;
            for (int i = 0; i < count; i++) {
                placed.add(new SyncedLyrics.Line(i * step, lines.get(i).text()));
            }
            return placed;
        }

        int i = 0;
        long previous = -1;
        while (i < count) {
            Line line = lines.get(i);
            if (line.timed()) {
                previous = line.timeMs();
                placed.add(new SyncedLyrics.Line(previous, line.text()));
                i++;
                continue;
            }

            int end = i;
            while (end < count && !lines.get(end).timed()) end++;
            int gap = end - i;

            long from = Math.max(0, previous);
            long to = end < count ? lines.get(end).timeMs()
                    : known ? durationMs : from + UNTIMED_GAP_MS * (gap + 1);
            if (to < from) to = from;

            for (int k = 0; k < gap; k++) {
                long at = previous < 0
                        ? to * k / gap
                        : from + (to - from) * (k + 1) / (gap + 1);
                placed.add(new SyncedLyrics.Line(at, lines.get(i + k).text()));
            }
            i = end;
        }
        return placed;
    }

    public static Line parseTyped(String typed) {
        String raw = typed == null ? "" : typed.strip();
        Matcher matcher = TIMED_LINE.matcher(raw);
        if (matcher.matches()) {
            return new Line(parseTime(matcher.group(1)), matcher.group(2).strip());
        }
        return raw.isEmpty() ? null : new Line(null, raw);
    }

    public static Long parseTime(String raw) {
        if (raw == null) return null;
        Matcher matcher = TIME.matcher(raw.trim());
        if (!matcher.matches()) return null;

        long minutes = Long.parseLong(matcher.group(1));
        long seconds = Long.parseLong(matcher.group(2));
        String digits = matcher.group(3);
        long fraction = 0;
        if (digits != null) {
            long value = Long.parseLong(digits);
            fraction = switch (digits.length()) {
                case 1 -> value * 100;
                case 2 -> value * 10;
                default -> value;
            };
        }
        return (minutes * 60 + seconds) * 1000L + fraction;
    }

    public static String formatTime(long ms) {
        long seconds = ms / 1000;
        long centis = (ms % 1000) / 10;
        String base = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        return centis == 0 ? base : base + String.format(Locale.ROOT, ".%02d", centis);
    }

    public static LocalTrackSettings parse(String text) throws IOException {
        JsonNode root = JSON.readTree(text == null || text.isBlank() ? "{}" : text);
        if (root == null || !root.isObject()) throw new IOException("the file is not a JSON object");

        String name = root.path("name").isTextual() ? root.get("name").asText() : null;
        Integer volume = root.path("default-volume").isNumber()
                ? root.get("default-volume").asInt() : null;

        List<String> permissions = new ArrayList<>();
        JsonNode perms = root.path("permissions");
        if (perms.isArray()) {
            for (JsonNode one : perms) addPermissions(permissions, one.asText());
        } else if (perms.isValueNode() && !perms.isNull()) {
            addPermissions(permissions, perms.asText());
        }

        JsonNode rawMeta = root.path("metadata");
        Meta meta = new Meta(textOf(rawMeta, "title"), textOf(rawMeta, "author"),
                textOf(rawMeta, "text"), rawMeta.path("one-line").asBoolean(false));

        JsonNode lyrics = root.path("lyrics");
        boolean enabled = lyrics.path("enabled").asBoolean(true);
        boolean sync = lyrics.path("sync-with-time").asBoolean(true);

        List<Line> lines = new ArrayList<>();
        JsonNode raw = lyrics.path("lines");
        if (raw.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = raw.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                Long at = parseTime(field.getKey());
                String line = field.getValue().asText("").strip();
                lines.add(new Line(at, line));
            }
        } else if (raw.isArray()) {
            for (JsonNode one : raw) {
                Line line = parseTyped(one.asText(""));
                if (line != null) lines.add(line);
            }
        }
        return new LocalTrackSettings(name, volume, permissions, meta, enabled, sync, lines);
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    public static List<String> splitPermissions(String typed) {
        List<String> out = new ArrayList<>();
        addPermissions(out, typed);
        return out;
    }

    private static void addPermissions(List<String> into, String raw) {
        if (raw == null) return;
        for (String part : raw.split("[,;\\s]+")) {
            String clean = part.strip();
            if (!clean.isEmpty() && !into.contains(clean)) into.add(clean);
        }
    }

    public String write() {
        StringBuilder out = new StringBuilder("{\n");
        List<String> fields = new ArrayList<>();

        if (name != null) fields.add("    \"name\": " + quote(name));
        if (volume != null) fields.add("    \"default-volume\": " + volume);
        if (permissions.size() == 1) {
            fields.add("    \"permissions\": " + quote(permissions.get(0)));
        } else if (!permissions.isEmpty()) {
            List<String> quoted = permissions.stream().map(LocalTrackSettings::quote).toList();
            fields.add("    \"permissions\": [" + String.join(", ", quoted) + "]");
        }
        if (!meta.isEmpty()) fields.add(metaBlock());
        fields.add(lyricsBlock());

        out.append(String.join(",\n", fields)).append("\n}\n");
        return out.toString();
    }

    private String metaBlock() {
        List<String> entries = new ArrayList<>();
        entries.add("        \"one-line\": " + meta.oneLine());
        if (meta.title() != null) entries.add("        \"title\": " + quote(meta.title()));
        if (meta.author() != null) entries.add("        \"author\": " + quote(meta.author()));
        if (meta.text() != null) entries.add("        \"text\": " + quote(meta.text()));
        return "    \"metadata\": {\n" + String.join(",\n", entries) + "\n    }";
    }

    private String lyricsBlock() {
        StringBuilder block = new StringBuilder("    \"lyrics\": {\n");
        block.append("        \"enabled\": ").append(lyricsEnabled).append(",\n");
        block.append("        \"sync-with-time\": ").append(syncWithTime).append(",\n");

        if (lines.isEmpty()) {
            return block.append("        \"lines\": {}\n    }").toString();
        }

        List<String> entries = new ArrayList<>();
        if (keyedByTime()) {
            for (Line line : lines) {
                entries.add("            " + quote(formatTime(line.timeMs())) + ": " + quote(line.text()));
            }
            block.append("        \"lines\": {\n").append(String.join(",\n", entries)).append("\n        }\n");
        } else {
            for (Line line : lines) {
                entries.add("            " + quote(line.asTyped()));
            }
            block.append("        \"lines\": [\n").append(String.join(",\n", entries)).append("\n        ]\n");
        }
        return block.append("    }").toString();
    }

    private boolean keyedByTime() {
        Set<String> seen = new HashSet<>();
        for (Line line : lines) {
            if (!line.timed() || !seen.add(formatTime(line.timeMs()))) return false;
        }
        return true;
    }

    private static String quote(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
