package dev.valkdz.cdisc.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.lyrics.SyncedLyrics;
import dev.valkdz.cdisc.permission.PermissionRule;
import dev.valkdz.cdisc.permission.Perms;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
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

    private record Scan(List<String> names, Map<String, Path> renamed, Map<String, String> rawNames) {
        static final Scan EMPTY = new Scan(List.of(), Map.of(), Map.of());
    }

    private record Settings(Map<String, LocalTrackSettings> byFile, Map<String, String> byAlias) {
        static final Settings EMPTY = new Settings(Map.of(), Map.of());

        static Settings of(Map<String, LocalTrackSettings> byFile) {
            Map<String, String> byAlias = new HashMap<>();
            byFile.entrySet().stream()
                    .filter(e -> e.getValue().name() != null)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> byAlias.putIfAbsent(key(e.getValue().name()), e.getKey()));
            return new Settings(Map.copyOf(byFile), Map.copyOf(byAlias));
        }
    }

    private volatile Scan scan = Scan.EMPTY;
    private volatile Settings settings = Settings.EMPTY;
    private final Object settingsLock = new Object();
    private final Map<String, LocalTrackSettings> savedDuringScan = new HashMap<>();
    private final Set<String> badSettingsReported = ConcurrentHashMap.newKeySet();
    private volatile long indexedAt;
    private volatile boolean truncationReported;
    private volatile boolean encodingReported;

    private final Map<String, String> aliases = new ConcurrentHashMap<>();

    public LocalMusicLibrary(Main plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (!plugin.cdiscConfig().isLocalEnabled()) {
            root = null;
            scan = Scan.EMPTY;
            settings = Settings.EMPTY;
            return;
        }

        extensions = plugin.cdiscConfig().getLocalExtensions();
        maxFiles = plugin.cdiscConfig().getLocalMaxFiles();
        truncationReported = false;

        Path folder = resolveFolder(plugin.cdiscConfig().getLocalFolder());
        if (folder == null) {
            root = null;
            scan = Scan.EMPTY;
            settings = Settings.EMPTY;
            return;
        }

        if (!folder.equals(root)) {
            scan = Scan.EMPTY;
            settings = Settings.EMPTY;
        }
        badSettingsReported.clear();
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
        return scan.names();
    }

    private void rescan() {
        Path scanRoot = root;
        if (scanRoot == null || !scanning.compareAndSet(false, true)) return;

        indexedAt = System.currentTimeMillis();

        synchronized (settingsLock) {
            savedDuringScan.clear();
        }

        Runnable task = () -> {
            try {
                Map<String, LocalTrackSettings> found = new HashMap<>();
                scan = walk(scanRoot, found);
                synchronized (settingsLock) {
                    // A GUI save landing mid-walk may be newer than what the walk read from disk.
                    found.putAll(savedDuringScan);
                    settings = Settings.of(found);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Couldn't read the local music folder: " + e.getMessage());
            } finally {
                scanning.set(false);
            }
        };

        if (plugin.isEnabled()) {
            Tasks.async(plugin, task);
        } else {
            task.run();
        }
    }

    private Scan walk(Path scanRoot, Map<String, LocalTrackSettings> found) throws IOException {
        List<String> names = new ArrayList<>();
        Map<String, Path> renamed = new HashMap<>();
        Map<String, String> rawNames = new HashMap<>();
        URI base = scanRoot.toUri();
        boolean truncated = false;

        try (Stream<Path> walk = Files.walk(scanRoot, MAX_DEPTH)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(path)) continue;
                if (!hasAllowedExtension(path.getFileName().toString())) continue;
                if (names.size() >= maxFiles) {
                    truncated = true;
                    break;
                }
                String raw = scanRoot.relativize(path).toString().replace(File.separatorChar, '/');
                String real = realName(base, path, raw);
                names.add(real);
                LocalTrackSettings own = readSettings(path, real);
                if (own != null) found.put(real, own);
                if (!real.equals(raw)) {
                    renamed.put(real, path);
                    rawNames.put(raw, real);
                }
            }
        }

        if (!renamed.isEmpty() && !encodingReported) {
            encodingReported = true;
            plugin.getLogger().warning("Some file names in the local music folder can't be read in this "
                    + "server's encoding (" + System.getProperty("sun.jnu.encoding") + "). CDisc plays "
                    + "them anyway; start Java with LANG=C.UTF-8 so everything else can read them too.");
        }

        if (truncated && !truncationReported) {
            truncationReported = true;
            plugin.getLogger().warning("The local music folder holds more than " + maxFiles
                    + " playable files; the rest are ignored. Raise local.max-files in sources.yml.");
        }

        names.sort(Comparator.comparing(LocalMusicLibrary::key));
        return new Scan(List.copyOf(names), Map.copyOf(renamed), Map.copyOf(rawNames));
    }

    private LocalTrackSettings readSettings(Path audio, String real) {
        Path file = settingsPathOf(audio);
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return LocalTrackSettings.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            if (badSettingsReported.add(real)) {
                plugin.getLogger().warning("Couldn't read " + real + LocalTrackSettings.EXTENSION
                        + ": " + e.getMessage() + ". The file plays with no settings of its own.");
            }
            return null;
        }
    }

    private static Path settingsPathOf(Path audio) {
        Path name = audio.getFileName();
        if (name == null) return null;
        try {
            return audio.resolveSibling(name + LocalTrackSettings.EXTENSION);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    public LocalTrackSettings settingsOf(String relative) {
        return relative == null ? null : settings.byFile().get(clean(relative));
    }

    public LocalTrackSettings settingsOf(AudioTrack track) {
        String address = addressOf(track);
        return address == null ? null : settingsOf(address.substring(PREFIX.length()));
    }

    public LocalTrackSettings.Shown shown(String query, String title, String author) {
        String relative = stripPrefix(query);
        LocalTrackSettings own = relative == null ? null : settingsOf(find(relative));
        return own == null ? new LocalTrackSettings.Shown(title, author) : own.meta().apply(title, author);
    }

    public SyncedLyrics lyricsOf(AudioTrack track) {
        LocalTrackSettings own = settingsOf(track);
        return own == null ? null : own.lyrics(track.getDuration());
    }

    public void saveSettings(String relative, LocalTrackSettings updated) throws IOException {
        Path base = root;
        if (base == null) throw new IOException("the local music folder is off");

        String real = clean(relative);
        Path audio = scan.renamed().get(real);
        if (audio == null) audio = base.resolve(real).normalize();
        if (!audio.startsWith(base)) throw new IOException("outside the music folder");

        Path file = settingsPathOf(audio);
        if (file == null) throw new IOException("no file name");
        Path temp = Files.createTempFile(file.getParent(), ".cdisc-", ".part");
        try {
            Files.writeString(temp, updated.write(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }

        synchronized (settingsLock) {
            savedDuringScan.put(real, updated);
            Map<String, LocalTrackSettings> byFile = new HashMap<>(settings.byFile());
            byFile.put(real, updated);
            settings = Settings.of(byFile);
        }
    }

    public String ownerOfName(String name) {
        return name == null ? null : settings.byAlias().get(key(name.trim()));
    }

    public boolean mayUse(CommandSender sender, String relative) {
        LocalTrackSettings own = settingsOf(relative);
        if (own == null || own.permissions().isEmpty() || sender == null) return true;
        return Perms.isAdmin(sender) || PermissionRule.parse(own.permissions()).test(sender);
    }

    public String shownName(String relative) {
        LocalTrackSettings own = settingsOf(relative);
        return own != null && own.name() != null && relative.equals(ownerOfName(own.name()))
                ? own.name() : relative;
    }

    static String realName(URI base, Path path, String raw) {
        URI relative = base.relativize(path.toUri());
        String decoded = relative.isAbsolute() ? null : relative.getPath();
        return decoded == null || decoded.isEmpty() ? raw : decoded;
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

        String aliased = settings.byAlias().get(needle);
        if (aliased != null && index().contains(aliased)) return aliased;

        String withoutExtension = null;
        for (String entry : index()) {
            String lower = key(entry);
            if (lower.equals(needle)) return entry;

            int dot = lower.lastIndexOf('.');
            if (dot > 0 && lower.substring(0, dot).equals(needle)) {

                if (withoutExtension == null) withoutExtension = entry;
            }
        }
        return withoutExtension;
    }

    public List<String> search(String name, int limit) {
        return search(name, limit, entry -> true);
    }

    public List<String> search(String name, int limit, Predicate<String> allowed) {
        if (limit <= 0) return List.of();
        String needle = needleOf(name == null ? "" : name);
        List<String> entries = index();

        if (needle.isEmpty()) {
            return entries.stream().filter(allowed).limit(limit).toList();
        }

        Set<String> ranked = new LinkedHashSet<>();
        for (int rule = 0; rule < 3 && ranked.size() < limit; rule++) {
            for (String entry : entries) {
                if (!allowed.test(entry)) continue;
                String lower = key(baseName(entry));
                String shown = key(shownName(entry));
                boolean hit = switch (rule) {
                    case 0 -> lower.equals(needle) || stripExtension(lower).equals(needle)
                            || shown.equals(needle);
                    case 1 -> lower.startsWith(needle) || shown.startsWith(needle);
                    default -> key(entry).contains(needle) || shown.contains(needle);
                };
                if (hit && ranked.add(entry) && ranked.size() >= limit) break;
            }
        }
        return List.copyOf(ranked);
    }

    public List<String> complete(String typed, int limit, CommandSender sender) {
        String name = stripPrefix(typed);
        if (name == null) return List.of();

        List<String> shown = new ArrayList<>();
        for (String entry : index()) {
            if (mayUse(sender, entry)) shown.add(shownName(entry));
        }
        return completeAmong(shown, typed, name, limit);
    }

    public List<String> completeFiles(String typed, int limit) {
        return completeAmong(index(), typed, typed, limit);
    }

    private static List<String> completeAmong(List<String> names, String typed, String name, int limit) {
        if (limit <= 0) return List.of();

        String needle = needleOf(name);
        List<String> out = new ArrayList<>();
        for (String entry : names) {
            if (entry.length() < name.length()
                    || !key(entry.substring(0, name.length())).equals(needle)) continue;
            out.add(typed + entry.substring(name.length()));
            if (out.size() >= limit) break;
        }
        return out;
    }

    public File fileFor(String relative) {
        Path base = root;
        if (base == null || relative == null || relative.isBlank()) return null;

        Path renamed = scan.renamed().get(clean(relative));
        if (renamed != null) {
            File file = openable(renamed, clean(relative));
            return file != null && file.isFile() && file.canRead() ? file : null;
        }

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

    // The walked Path still holds the name's real bytes; its String form may not.
    // Lavaplayer opens files by String, so such a file is reached through an ASCII link.
    private synchronized File openable(Path actual, String real) {
        File direct = actual.toFile();
        if (direct.isFile()) return direct;

        Path dir = plugin.getDataFolder().toPath().resolve("local-links");
        String name = UUID.nameUUIDFromBytes(real.getBytes(StandardCharsets.UTF_8))
                + "." + real.substring(real.lastIndexOf('.') + 1);
        Path link = dir.resolve(name);

        try {
            Files.createDirectories(dir);
            Files.deleteIfExists(link);
            try {
                Files.createSymbolicLink(link, actual);
            } catch (IOException | UnsupportedOperationException e) {
                Files.createLink(link, actual);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't reach local file '" + real + "': " + e.getMessage());
            return null;
        }

        File file = link.toFile();
        aliases.put(file.getAbsolutePath(), real);
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

        String aliased = aliases.get(rawPath);
        if (aliased != null) return aliased;

        Path path;
        try {
            path = Paths.get(rawPath);
        } catch (InvalidPathException e) {
            return null;
        }

        if (!path.isAbsolute()) return null;

        path = path.normalize();
        if (!path.startsWith(base) || path.equals(base)) return null;
        String relative = base.relativize(path).toString().replace(File.separatorChar, '/');
        return scan.rawNames().getOrDefault(relative, relative);
    }

    private static String clean(String raw) {
        String path = raw.trim().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private static String needleOf(String raw) {
        return key(clean(raw));
    }

    static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replace('ё', 'е');
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
