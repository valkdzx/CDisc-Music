package dev.valkdz.cdisc.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.valkdz.cdisc.Main;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public final class LocalMusicLibrary {

    public static final String PREFIX = "local:";

    private static final String ALT_PREFIX = "music:";

    private static final long REFRESH_INTERVAL_MS = 30_000L;

    private static final int MAX_DEPTH = 8;

    private final Main plugin;
    private final AtomicBoolean scanning = new AtomicBoolean();

    private volatile Path root;
    private volatile Set<String> extensions = Set.of();
    private volatile int maxFiles;

    private volatile List<String> index = List.of();
    private volatile long indexedAt;
    private volatile boolean truncationReported;

    public LocalMusicLibrary(Main plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (!plugin.cdiscConfig().isLocalEnabled()) {
            root = null;
            index = List.of();
            return;
        }

        extensions = plugin.cdiscConfig().getLocalExtensions();
        maxFiles = plugin.cdiscConfig().getLocalMaxFiles();
        truncationReported = false;

        Path folder = resolveFolder(plugin.cdiscConfig().getLocalFolder());
        if (folder == null) {
            root = null;
            index = List.of();
            return;
        }

        if (!folder.equals(root)) index = List.of();
        root = folder;
        indexedAt = 0;
        rescan();
    }

    private Path resolveFolder(String configured) {
        try {
            Path path = Paths.get(configured);
            if (!path.isAbsolute()) {
                path = plugin.getDataFolder().toPath().resolve(path);
            }
            Files.createDirectories(path);
            return path.toRealPath();
        } catch (IOException | InvalidPathException e) {
            plugin.getLogger().warning("Local music folder '" + configured
                    + "' can't be used: " + e.getMessage() + ". The local: source is off.");
            return null;
        }
    }

    public boolean isEnabled() {
        return root != null;
    }

    public Path root() {
        return root;
    }

    public static boolean isLocalQuery(String query) {
        return stripPrefix(query) != null;
    }

    public static String stripPrefix(String query) {
        if (query == null) return null;
        String trimmed = query.trim();
        if (trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return trimmed.substring(PREFIX.length()).trim();
        }
        if (trimmed.regionMatches(true, 0, ALT_PREFIX, 0, ALT_PREFIX.length())) {
            return trimmed.substring(ALT_PREFIX.length()).trim();
        }
        return null;
    }

    public List<String> index() {
        if (root != null && System.currentTimeMillis() - indexedAt > REFRESH_INTERVAL_MS) {
            rescan();
        }
        return index;
    }

    private void rescan() {
        Path scanRoot = root;
        if (scanRoot == null || !scanning.compareAndSet(false, true)) return;

        indexedAt = System.currentTimeMillis();

        Runnable scan = () -> {
            try {
                index = walk(scanRoot);
            } catch (Exception e) {
                plugin.getLogger().warning("Couldn't read the local music folder: " + e.getMessage());
            } finally {
                scanning.set(false);
            }
        };

        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, scan);
        } else {
            scan.run();
        }
    }

    private List<String> walk(Path scanRoot) throws IOException {
        List<String> found = new ArrayList<>();
        boolean truncated = false;

        try (Stream<Path> walk = Files.walk(scanRoot, MAX_DEPTH)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(path)) continue;
                if (!hasAllowedExtension(path.getFileName().toString())) continue;
                if (found.size() >= maxFiles) {
                    truncated = true;
                    break;
                }
                found.add(scanRoot.relativize(path).toString().replace(File.separatorChar, '/'));
            }
        }

        if (truncated && !truncationReported) {
            truncationReported = true;
            plugin.getLogger().warning("The local music folder holds more than " + maxFiles
                    + " playable files; the rest are ignored. Raise local.max-files in sources.yml.");
        }

        found.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
        return List.copyOf(found);
    }

    private boolean hasAllowedExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return false;
        return extensions.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public String find(String name) {
        if (name == null || name.isBlank()) return null;
        String needle = needleOf(name);
        if (needle.isEmpty()) return null;

        String withoutExtension = null;
        for (String entry : index()) {
            String lower = entry.toLowerCase(Locale.ROOT);
            if (lower.equals(needle)) return entry;

            int dot = lower.lastIndexOf('.');
            if (dot > 0 && lower.substring(0, dot).equals(needle)) {

                if (withoutExtension == null) withoutExtension = entry;
            }
        }
        return withoutExtension;
    }

    public List<String> search(String name, int limit) {
        if (limit <= 0) return List.of();
        String needle = needleOf(name == null ? "" : name);
        List<String> entries = index();

        if (needle.isEmpty()) {
            return List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
        }

        Set<String> ranked = new LinkedHashSet<>();
        for (int rule = 0; rule < 3 && ranked.size() < limit; rule++) {
            for (String entry : entries) {
                String lower = baseName(entry).toLowerCase(Locale.ROOT);
                boolean hit = switch (rule) {
                    case 0 -> lower.equals(needle) || stripExtension(lower).equals(needle);
                    case 1 -> lower.startsWith(needle);
                    default -> entry.toLowerCase(Locale.ROOT).contains(needle);
                };
                if (hit && ranked.add(entry) && ranked.size() >= limit) break;
            }
        }
        return List.copyOf(ranked);
    }

    public List<String> complete(String typed, int limit) {
        String name = stripPrefix(typed);
        if (name == null || limit <= 0) return List.of();

        String needle = needleOf(name);
        List<String> out = new ArrayList<>();
        for (String entry : index()) {
            if (!entry.toLowerCase(Locale.ROOT).startsWith(needle)) continue;
            out.add(typed + entry.substring(name.length()));
            if (out.size() >= limit) break;
        }
        return out;
    }

    public File fileFor(String relative) {
        Path base = root;
        if (base == null || relative == null || relative.isBlank()) return null;

        Path candidate;
        try {
            candidate = base.resolve(clean(relative)).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!candidate.startsWith(base)) return null;

        File file = candidate.toFile();
        if (!file.isFile() || !file.canRead()) return null;
        if (!hasAllowedExtension(file.getName())) return null;
        return file;
    }

    public String resolveToPath(String query) {
        String name = stripPrefix(query);
        if (name == null || !isEnabled()) return null;

        String entry = find(name);
        File file = fileFor(entry == null ? name : entry);
        return file == null ? null : file.getAbsolutePath();
    }

    public String titleFor(String relative, String tagged) {
        boolean usable = tagged != null && !tagged.isBlank()
                && !tagged.equals(MediaContainerDetection.UNKNOWN_TITLE);
        if (usable) return tagged;
        return stripExtension(baseName(relative == null ? "" : relative));
    }

    public String addressOf(AudioTrack track) {
        if (track == null) return null;
        String relative = relativize(track.getInfo().identifier);
        if (relative == null) relative = relativize(track.getInfo().uri);
        return relative == null ? null : PREFIX + relative;
    }

    private String relativize(String rawPath) {
        Path base = root;
        if (base == null || rawPath == null || rawPath.isBlank()) return null;

        Path path;
        try {
            path = Paths.get(rawPath);
        } catch (InvalidPathException e) {
            return null;
        }

        if (!path.isAbsolute()) return null;

        path = path.normalize();
        if (!path.startsWith(base) || path.equals(base)) return null;
        return base.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static String clean(String raw) {
        String path = raw.trim().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private static String needleOf(String raw) {
        return clean(raw).toLowerCase(Locale.ROOT);
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
