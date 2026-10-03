package dev.valkdz.cdisc.feature.lyrics;

import dev.valkdz.cdisc.config.Config;
import dev.valkdz.cdisc.util.Json;

public record HologramStyle(
        int backgroundColor,
        int backgroundOpacity,
        int brightness,
        String textPrefix,
        int textOpacity,
        String currentPrefix,
        int currentOpacity,
        String trackPrefix,
        int trackOpacity,
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


    private record Cached(Config config, int generation, HologramStyle style) {
    }

    private static volatile Cached cached;

    public static HologramStyle fromConfig(Config config) {
        Cached last = cached;
        int generation = config.generation();
        if (last != null && last.config == config && last.generation == generation) return last.style;
        HologramStyle style = read(config);
        cached = new Cached(config, generation, style);
        return style;
    }

    private static HologramStyle read(Config config) {
        return new HologramStyle(
                0x000000,
                config.getLyricsBackgroundOpacity(),
                config.getLyricsBrightness(),
                config.getLyricsOtherColor(),
                config.getLyricsTextOpacity(),
                config.getLyricsCurrentColor(),
                config.getLyricsTextOpacity(),
                config.getLyricsTrackColor(),
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

    public String trackColor() {
        return LyricsStyle.withOpacity(trackPrefix, trackOpacity);
    }

    public HologramStyle withTrackPrefix(String value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, safe(value), trackOpacity, size, height,
                lineWidth, linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withTrackOpacity(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, clamp(value, 0, 255), size,
                height, lineWidth, linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide,
                countdown);
    }

    public LyricsStyle lyricsStyle() {
        return LyricsStyle.of(
                LyricsStyle.withOpacity(currentPrefix, currentOpacity),
                LyricsStyle.withOpacity(textPrefix, textOpacity));
    }

    public HologramStyle withBackgroundColor(int rgb) {
        return new HologramStyle(rgb & 0xFFFFFF, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withBackgroundOpacity(int value) {
        return new HologramStyle(backgroundColor, clamp(value, 0, 255), brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withBrightness(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity,
                value < 0 ? BRIGHTNESS_WORLD : Math.min(15, value), textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withTextPrefix(String value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, safe(value),
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withTextOpacity(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                clamp(value, 0, 255), currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withCurrentPrefix(String value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, safe(value), currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withCurrentOpacity(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, clamp(value, 0, 255), trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withSize(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                clamp(value, 1, 10), height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withHeight(double value) {
        double clean = Math.max(0.0, Math.min(5.0, Math.round(value * 10.0) / 10.0));
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, clean, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withLineWidth(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, clamp(value, 40, 600),
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withLinesBefore(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                clamp(value, 0, 6), linesAfter, shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withLinesAfter(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, clamp(value, 0, 6), shadow, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withShadow(boolean value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, value, seeThrough, fadeTicks, slide, countdown);
    }

    public HologramStyle withSeeThrough(boolean value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, value, fadeTicks, slide, countdown);
    }

    public HologramStyle withFadeTicks(int value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, clamp(value, 0, 20), slide, countdown);
    }

    public HologramStyle withSlide(boolean value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, value, countdown);
    }

    public HologramStyle withCountdown(boolean value) {
        return new HologramStyle(backgroundColor, backgroundOpacity, brightness, textPrefix,
                textOpacity, currentPrefix, currentOpacity, trackPrefix, trackOpacity,
                size, height, lineWidth,
                linesBefore, linesAfter, shadow, seeThrough, fadeTicks, slide, value);
    }

    public Json toJson() {
        Json node = Json.object();
        node.put("background-color", backgroundColor);
        node.put("background-opacity", backgroundOpacity);
        node.put("brightness", brightness);
        node.put("text-prefix", textPrefix);
        node.put("text-opacity", textOpacity);
        node.put("current-prefix", currentPrefix);
        node.put("current-opacity", currentOpacity);
        node.put("track-prefix", trackPrefix);
        node.put("track-opacity", trackOpacity);
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

    public static HologramStyle fromJson(Json node, HologramStyle fallback) {
        if (node == null || !node.isObject()) return fallback;

        return new HologramStyle(
                node.path("background-color").asInt(fallback.backgroundColor) & 0xFFFFFF,
                clamp(node.path("background-opacity").asInt(fallback.backgroundOpacity), 0, 255),
                clampBrightness(node.path("brightness").asInt(fallback.brightness)),
                node.path("text-prefix").asText(fallback.textPrefix),
                clamp(node.path("text-opacity").asInt(fallback.textOpacity), 0, 255),
                node.path("current-prefix").asText(fallback.currentPrefix),
                clamp(node.path("current-opacity").asInt(fallback.currentOpacity), 0, 255),
                node.path("track-prefix").asText(fallback.trackPrefix),
                clamp(node.path("track-opacity").asInt(fallback.trackOpacity), 0, 255),
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
            return fromJson(Json.parse(json), fallback);
        } catch (Exception e) {
            return fallback;
        }
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
