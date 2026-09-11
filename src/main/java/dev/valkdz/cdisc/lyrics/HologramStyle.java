package dev.valkdz.cdisc.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.valkdz.cdisc.util.Config;

public record HologramStyle(
        int backgroundColor,
        int backgroundOpacity,
        int brightness,
        String textPrefix,
        int textOpacity,
        String currentPrefix,
        int currentOpacity,
        int size,
        double height,
        int lineWidth,
        int linesBefore,
        int linesAfter,
        boolean shadow,
        boolean seeThrough,
        int fadeTicks,
        boolean slide,
        boolean countdown) {

    public static final int BRIGHTNESS_WORLD = -1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static HologramStyle fromConfig(Config config) {
        return new HologramStyle(
                0x000000,
                config.getLyricsBackgroundOpacity(),
                config.getLyricsBrightness(),
                config.getLyricsOtherColor(),
                config.getLyricsTextOpacity(),
                config.getLyricsCurrentColor(),
                config.getLyricsTextOpacity(),
                config.getLyricsSize(),
                config.getLyricsHeight(),
                config.getLyricsLineWidth(),
                config.getLyricsLinesBefore(),
                config.getLyricsLinesAfter(),
                config.isLyricsShadowed(),
                config.isLyricsSeeThrough(),
                config.getLyricsFadeTicks(),
                config.isLyricsSlideEnabled(),
                config.isLyricsCountdownEnabled());
    }

    public LyricsStyle lyricsStyle() {
        return LyricsStyle.of(
                LyricsStyle.withOpacity(currentPrefix, currentOpacity),
                LyricsStyle.withOpacity(textPrefix, textOpacity));
    }

    public HologramStyle withBackgroundColor(int rgb) {
        return new HologramStyle(rgb & 0xFFFFFF, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withBackgroundOpacity(int value) {
        return new HologramStyle(backgroundColor, clamp(value, 0, 255), brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withBrightness(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity,
                value < 0 ? BRIGHTNESS_WORLD : Math.min(15, value), textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withTextPrefix(String value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, safe(value),
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withTextOpacity(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                clamp(value, 0, 255), currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withCurrentPrefix(String value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, safe(value), currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withCurrentOpacity(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, clamp(value, 0, 255), size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withSize(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, clamp(value, 1, 10), height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withHeight(double value) {
        double clean = Math.max(0.0, Math.min(5.0, Math.round(value * 10.0) / 10.0));
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, clean, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withLineWidth(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, clamp(value, 40, 600),
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withLinesBefore(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                clamp(value, 0, 6), linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withLinesAfter(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, clamp(value, 0, 6), shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withShadow(boolean value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, value, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withSeeThrough(boolean value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, shadow, value, fadeTicks, slide, countdown);
    }

    public HologramStyle withFadeTicks(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, clamp(value, 0, 20), slide, countdown);
    }

    public HologramStyle withSlide(boolean value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, value, countdown);
    }

    public HologramStyle withCountdown(boolean value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, value);
    }

    public ObjectNode toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("background-color", backgroundColor);
        node.put("background-opacity", backgroundOpacity);
        node.put("brightness", brightness);
        node.put("text-prefix", textPrefix);
        node.put("text-opacity", textOpacity);
        node.put("current-prefix", currentPrefix);
        node.put("current-opacity", currentOpacity);
        node.put("size", size);
        node.put("height", height);
        node.put("line-width", lineWidth);
        node.put("lines-before", linesBefore);
        node.put("lines-after", linesAfter);
        node.put("shadow", shadow);
        node.put("see-through", seeThrough);
        node.put("fade-ticks", fadeTicks);
        node.put("slide", slide);
        node.put("countdown", countdown);
        return node;
    }

    public String toJsonString() {
        return toJson().toString();
    }

    public static HologramStyle fromJson(JsonNode node, HologramStyle fallback) {
        if (node == null || !node.isObject()) return fallback;

        return new HologramStyle(
                node.path("background-color").asInt(fallback.backgroundColor) & 0xFFFFFF,
                clamp(node.path("background-opacity").asInt(fallback.backgroundOpacity), 0, 255),
                clampBrightness(node.path("brightness").asInt(fallback.brightness)),
                node.path("text-prefix").asText(fallback.textPrefix),
                clamp(node.path("text-opacity").asInt(fallback.textOpacity), 0, 255),
                node.path("current-prefix").asText(fallback.currentPrefix),
                clamp(node.path("current-opacity").asInt(fallback.currentOpacity), 0, 255),
                clamp(node.path("size").asInt(fallback.size), 1, 10),
                Math.max(0.0, Math.min(5.0, node.path("height").asDouble(fallback.height))),
                clamp(node.path("line-width").asInt(fallback.lineWidth), 40, 600),
                clamp(node.path("lines-before").asInt(fallback.linesBefore), 0, 6),
                clamp(node.path("lines-after").asInt(fallback.linesAfter), 0, 6),
                node.path("shadow").asBoolean(fallback.shadow),
                node.path("see-through").asBoolean(fallback.seeThrough),
                clamp(node.path("fade-ticks").asInt(fallback.fadeTicks), 0, 20),
                node.path("slide").asBoolean(fallback.slide),
                node.path("countdown").asBoolean(fallback.countdown));
    }

    public static HologramStyle fromJsonString(String json, HologramStyle fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return fromJson(MAPPER.readTree(json), fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampBrightness(int value) {
        return value < 0 ? BRIGHTNESS_WORLD : Math.min(15, value);
    }
}
