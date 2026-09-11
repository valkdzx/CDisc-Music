package dev.valkdz.cdisc.lyrics;

import java.util.Locale;

public final class LyricsStyle {

    private static final int[] LEGACY_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private static final String FORMAT_CODES = "klmnor";

    private final String currentPrefix;
    private final String otherPrefix;
    private final int currentRgb;
    private final int otherRgb;
    private final String currentFormats;
    private final String otherFormats;

    private LyricsStyle(String currentPrefix, String otherPrefix,
                        int currentRgb, int otherRgb,
                        String currentFormats, String otherFormats) {
        this.currentPrefix = currentPrefix;
        this.otherPrefix = otherPrefix;
        this.currentRgb = currentRgb;
        this.otherRgb = otherRgb;
        this.currentFormats = currentFormats;
        this.otherFormats = otherFormats;
    }

    public static LyricsStyle of(String current, String other) {
        Parsed lit = parse(current);
        Parsed dim = parse(other);
        return new LyricsStyle(current, other, lit.rgb, dim.rgb, lit.formats, dim.formats);
    }

    public String current() {
        return currentPrefix;
    }

    public String other() {
        return otherPrefix;
    }

    public boolean canBlend() {
        return currentRgb >= 0 && otherRgb >= 0;
    }

    public String rising(float progress) {
        if (!canBlend()) return progress >= 1f ? currentPrefix : otherPrefix;
        return hex(blend(otherRgb, currentRgb, clamp(progress)))
                + (progress >= 0.5f ? currentFormats : otherFormats);
    }

    public String falling(float progress) {
        if (!canBlend()) return progress >= 1f ? otherPrefix : currentPrefix;
        return hex(blend(currentRgb, otherRgb, clamp(progress)))
                + (progress >= 0.5f ? otherFormats : currentFormats);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int blend(int from, int to, float t) {
        int r = channel(from, to, 16, t);
        int g = channel(from, to, 8, t);
        int b = channel(from, to, 0, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int to, int shift, float t) {
        int a = (from >> shift) & 0xFF;
        int b = (to >> shift) & 0xFF;
        return Math.round(a + t * (b - a));
    }

    private static String hex(int rgb) {
        String digits = String.format(Locale.ROOT, "%06x", rgb & 0xFFFFFF);
        StringBuilder out = new StringBuilder(14).append('§').append('x');
        for (int i = 0; i < 6; i++) {
            out.append('§').append(digits.charAt(i));
        }
        return out.toString();
    }

    public static String withOpacity(String prefix, int opacity) {
        int alpha = Math.max(0, Math.min(255, opacity));
        if (alpha >= 255) return prefix;

        Parsed parsed = parse(prefix);
        if (parsed.rgb < 0) return prefix;

        return hex(blend(0x000000, parsed.rgb, alpha / 255f)) + parsed.formats;
    }

    public static char colorCode(String prefix) {
        if (prefix == null) return 0;
        for (int i = 0; i + 1 < prefix.length(); i++) {
            if (prefix.charAt(i) != '§') continue;
            char code = Character.toLowerCase(prefix.charAt(i + 1));
            if (code == 'x' || code == '#') return 0;
            if (Character.digit(code, 16) >= 0) return code;
        }
        return 0;
    }

    public static String withColor(String prefix, char code) {
        return '§' + String.valueOf(code) + formatsOf(prefix);
    }

    public static boolean hasFormat(String prefix, char code) {
        return formatsOf(prefix).indexOf(Character.toLowerCase(code)) >= 0;
    }

    public static String toggleFormat(String prefix, char code) {
        char clean = Character.toLowerCase(code);
        if (FORMAT_CODES.indexOf(clean) < 0) return prefix;

        String colour = colourPartOf(prefix);
        String formats = parse(prefix).formats;

        String one = "§" + clean;
        formats = formats.contains(one) ? formats.replace(one, "") : formats + one;
        return colour + formats;
    }

    private static String formatsOf(String prefix) {
        return parse(prefix).formats;
    }

    private static String colourPartOf(String prefix) {
        if (prefix == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 1 < prefix.length(); i++) {
            if (prefix.charAt(i) != '§') continue;
            char code = Character.toLowerCase(prefix.charAt(i + 1));

            if (code == 'x' && i + 13 < prefix.length()) {
                out.setLength(0);
                out.append(prefix, i, i + 14);
                i += 13;
                continue;
            }
            if (Character.digit(code, 16) >= 0) {
                out.setLength(0);
                out.append('§').append(code);
                i++;
            }
        }
        return out.toString();
    }

    private record Parsed(int rgb, String formats) {
    }

    private static Parsed parse(String prefix) {
        if (prefix == null || prefix.isEmpty()) return new Parsed(-1, "");

        int rgb = -1;
        StringBuilder formats = new StringBuilder();

        for (int i = 0; i < prefix.length(); i++) {
            if (prefix.charAt(i) != '§' || i + 1 >= prefix.length()) continue;
            char code = Character.toLowerCase(prefix.charAt(i + 1));

            if (code == 'x' && i + 13 < prefix.length()) {

                StringBuilder digits = new StringBuilder(6);
                for (int d = 0; d < 6; d++) {
                    digits.append(prefix.charAt(i + 3 + d * 2));
                }
                rgb = parseHex(digits.toString(), rgb);
                i += 13;
                continue;
            }

            if (code == '#' && i + 7 < prefix.length()) {
                rgb = parseHex(prefix.substring(i + 2, i + 8), rgb);
                i += 7;
                continue;
            }

            int index = Character.digit(code, 16);
            if (index >= 0) {
                rgb = LEGACY_RGB[index];
                i++;
                continue;
            }

            if (FORMAT_CODES.indexOf(code) >= 0) {
                formats.append('§').append(code);
                i++;
            }
        }

        return new Parsed(rgb, formats.toString());
    }

    private static int parseHex(String digits, int fallback) {
        try {
            return Integer.parseInt(digits, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
