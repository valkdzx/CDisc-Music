package dev.valkdz.cdisc.feature.library;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.disc.ItemUtils;
import dev.valkdz.cdisc.util.Json;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class TrackLibrary {

    public static final String FOLDER = "library";

    // query: the address a disc of this track holds, which is also what tells two tracks apart.
    public record Track(String query, String fallback, String title, String author, String fetch,
                        long lengthMs, boolean live, long addedAt) {

        public ItemUtils.Hint hint() {
            return lengthMs > 0 || live ? new ItemUtils.Hint(null, lengthMs, live, null, 0, null) : null;
        }

        public String name() {
            return author == null || author.isBlank() ? title : author + " - " + title;
        }
    }

    public enum Added {
        ADDED,
        ALREADY,
        FULL
    }

    private final Main plugin;
    private final File folder;
    private final Map<UUID, List<Track>> loaded = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cdisc-library");
        thread.setDaemon(true);
        return thread;
    });

    public TrackLibrary(Main plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), FOLDER);
    }

    public boolean isEnabled() {
        return plugin.cdiscConfig().getLibrarySize() > 0;
    }

    public int capacity() {
        return plugin.cdiscConfig().getLibrarySize();
    }

    public List<Track> tracks(UUID player) {
        List<Track> tracks = of(player);
        synchronized (tracks) {
            return List.copyOf(tracks);
        }
    }

    public Track find(UUID player, String query) {
        if (query == null) return null;
        List<Track> tracks = of(player);
        synchronized (tracks) {
            for (Track track : tracks) {
                if (track.query().equals(query)) return track;
            }
        }
        return null;
    }

    public boolean contains(UUID player, String query) {
        return find(player, query) != null;
    }

    public Added add(UUID player, Track track) {
        List<Track> tracks = of(player);
        synchronized (tracks) {
            for (Track known : tracks) {
                if (known.query().equals(track.query())) return Added.ALREADY;
            }
            if (tracks.size() >= capacity()) return Added.FULL;
            tracks.add(0, track);
        }
        save(player);
        return Added.ADDED;
    }

    public boolean remove(UUID player, String query) {
        List<Track> tracks = of(player);
        boolean removed;
        synchronized (tracks) {
            removed = tracks.removeIf(track -> track.query().equals(query));
        }
        if (removed) save(player);
        return removed;
    }

    public void unload(UUID player) {
        loaded.remove(player);
    }

    public void shutdown() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Track> of(UUID player) {
        return loaded.computeIfAbsent(player, this::readAfterWrites);
    }

    // Read on the writer's thread: a save still queued from before a quick rejoin would
    // otherwise be read stale, and the next save would drop what it held.
    private List<Track> readAfterWrites(UUID player) {
        try {
            return writer.submit(() -> read(player)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.RejectedExecutionException ignored) {
        }
        return read(player);
    }

    private File fileOf(UUID player) {
        return new File(folder, player + ".json");
    }

    private List<Track> read(UUID player) {
        List<Track> tracks = Collections.synchronizedList(new ArrayList<>());
        File file = fileOf(player);
        if (!file.isFile()) return tracks;
        try {
            Json root = Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            for (Json entry : root.get("tracks").values()) {
                String query = entry.get("query").text();
                if (query == null || query.isBlank()) continue;
                tracks.add(new Track(query, entry.get("fallback").text(), entry.get("title").asText("No name"),
                        entry.get("author").text(), entry.get("fetch").text(), entry.get("length").asLong(0),
                        entry.get("live").asBoolean(false), entry.get("added").asLong(0)));
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "The library of " + player + " could not be read", e);
        }
        return tracks;
    }

    private void save(UUID player) {
        List<Track> snapshot = tracks(player);
        Json root = Json.object();
        Json list = root.putArray("tracks");
        for (Track track : snapshot) {
            Json entry = Json.object().put("query", track.query()).put("title", track.title())
                    .put("length", track.lengthMs()).put("live", track.live()).put("added", track.addedAt());
            if (track.fallback() != null) entry.put("fallback", track.fallback());
            if (track.author() != null) entry.put("author", track.author());
            if (track.fetch() != null) entry.put("fetch", track.fetch());
            list.add(entry);
        }
        String text = root.toPrettyString();
        try {
            writer.execute(() -> write(player, text));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            write(player, text);
        }
    }

    private void write(UUID player, String text) {
        try {
            Files.createDirectories(folder.toPath());
            File file = fileOf(player);
            File temp = new File(folder, player + ".json.tmp");
            Files.writeString(temp.toPath(), text, StandardCharsets.UTF_8);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "The library of " + player + " could not be saved", e);
        }
    }
}
