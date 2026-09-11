package dev.valkdz.cdisc.audio;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class FileNames {

    private static final Map<String, String> TYPE_EXTENSIONS = Map.ofEntries(
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/mp3", "mp3"),
            Map.entry("audio/ogg", "ogg"),
            Map.entry("application/ogg", "ogg"),
            Map.entry("audio/vorbis", "ogg"),
            Map.entry("audio/opus", "opus"),
            Map.entry("audio/flac", "flac"),
            Map.entry("audio/x-flac", "flac"),
            Map.entry("audio/wav", "wav"),
            Map.entry("audio/x-wav", "wav"),
            Map.entry("audio/wave", "wav"),
            Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/x-m4a", "m4a"),
            Map.entry("audio/aac", "aac"),
            Map.entry("audio/aacp", "aac"),
            Map.entry("audio/webm", "webm"),
            Map.entry("video/webm", "webm"));

    private static final int MAX_NAME_LENGTH = 120;

    private FileNames() {
    }

    static String choose(String desired,
                         String contentDisposition,
                         String url,
                         String contentType,
                         Set<String> allowed) {

        String extension = extensionFromType(contentType);

        String fromAdmin = sanitise(desired);
        if (fromAdmin != null) {
            String named = ensureExtension(fromAdmin, extension, allowed);
            if (named != null) return named;
        }

        String fromHeader = sanitise(filenameOf(contentDisposition));
        if (fromHeader != null) {
            String named = ensureExtension(fromHeader, extension, allowed);
            if (named != null) return named;
        }

        String fromUrl = sanitise(lastSegmentOf(url));
        if (fromUrl != null) {
            String named = ensureExtension(fromUrl, extension, allowed);
            if (named != null) return named;
        }

        if (extension != null && allowed.contains(extension)) {
            return "download-" + System.currentTimeMillis() + "." + extension;
        }
        return null;
    }

    private static String ensureExtension(String name, String fromType, Set<String> allowed) {
        int dot = name.lastIndexOf('.');
        String existing = dot > 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : null;

        if (looksLikeExtension(existing)) {
            if (allowed.contains(existing)) return name;

            if (fromType != null && allowed.contains(fromType)) {
                return name.substring(0, dot) + "." + fromType;
            }
            return null;
        }

        if (fromType != null && allowed.contains(fromType)) {
            return name + "." + fromType;
        }
        return null;
    }

    private static boolean looksLikeExtension(String tail) {
        if (tail == null || tail.isEmpty() || tail.length() > 5) return false;

        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isLetterOrDigit(tail.charAt(i))) return false;
        }
        return true;
    }

    private static String extensionFromType(String contentType) {
        if (contentType == null || contentType.isBlank()) return null;
        String bare = contentType.toLowerCase(Locale.ROOT);
        int semicolon = bare.indexOf(';');
        if (semicolon >= 0) bare = bare.substring(0, semicolon);
        return TYPE_EXTENSIONS.get(bare.trim());
    }

    static String filenameOf(String header) {
        if (header == null) return null;
        String lower = header.toLowerCase(Locale.ROOT);

        int star = lower.indexOf("filename*=");
        if (star >= 0) {
            String value = header.substring(star + "filename*=".length()).trim();
            int quote = value.indexOf("''");
            if (quote >= 0) value = value.substring(quote + 2);
            value = stripQuotes(cutAtSemicolon(value));
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return value;
            }
        }

        int plain = lower.indexOf("filename=");
        if (plain < 0) return null;
        return stripQuotes(cutAtSemicolon(header.substring(plain + "filename=".length()).trim()));
    }

    private static String lastSegmentOf(String url) {
        if (url == null) return null;
        String path;
        try {
            path = new URI(url).getPath();
        } catch (Exception e) {
            return null;
        }
        if (path == null || path.isEmpty()) return null;

        int slash = path.lastIndexOf('/');
        String segment = slash >= 0 ? path.substring(slash + 1) : path;
        if (segment.isEmpty()) return null;
        try {
            return URLDecoder.decode(segment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }

    private static String sanitise(String raw) {
        if (raw == null) return null;
        String name = raw.trim();
        if (name.isEmpty()) return null;

        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);

        StringBuilder clean = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {

            if (c < 0x20 || c == 0x7F) continue;
            if (c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|') continue;
            clean.append(c);
        }

        while (clean.length() > 0 && (clean.charAt(0) == '.' || clean.charAt(0) == ' ')) {
            clean.deleteCharAt(0);
        }
        while (clean.length() > 0) {
            char last = clean.charAt(clean.length() - 1);
            if (last != ' ' && last != '.') break;
            clean.deleteCharAt(clean.length() - 1);
        }

        if (clean.length() == 0) return null;
        if (clean.length() > MAX_NAME_LENGTH) {

            String trimmed = clean.toString();
            int dot = trimmed.lastIndexOf('.');
            if (dot > 0 && trimmed.length() - dot <= 6) {
                String stem = trimmed.substring(0, dot);
                String ext = trimmed.substring(dot);
                int room = MAX_NAME_LENGTH - ext.length();
                trimmed = stem.substring(0, Math.max(1, Math.min(stem.length(), room))) + ext;
            } else {
                trimmed = trimmed.substring(0, MAX_NAME_LENGTH);
            }
            return trimmed;
        }
        return clean.toString();
    }

    private static String cutAtSemicolon(String value) {
        int semicolon = value.indexOf(';');
        return semicolon >= 0 ? value.substring(0, semicolon).trim() : value;
    }

    private static String stripQuotes(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
