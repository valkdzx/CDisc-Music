package dev.valkdz.cdisc.feature.karaoke;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.api.event.PlaybackStopEvent;
import dev.valkdz.cdisc.api.event.TrackStartEvent;
import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioLoadResultHandler;
import dev.valkdz.cdisc.audio.player.AudioPlayer;
import dev.valkdz.cdisc.audio.player.AudioPlaylist;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.audio.source.TrackLoader;
import dev.valkdz.cdisc.audio.source.youtube.SabrResolver;
import dev.valkdz.cdisc.audio.source.youtube.YouTubeSearch;
import dev.valkdz.cdisc.feature.broadcast.BroadcastTrack;
import dev.valkdz.cdisc.jukebox.AudioSession;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;
import java.util.logging.Level;

public final class TrackVersions implements Listener {

    public enum Kind {
        ORIGINAL,
        UNCENSORED,
        MINUS;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromKey(String key) {
            for (Kind kind : values()) {
                if (kind.key().equals(key)) return kind;
            }
            return null;
        }
    }

    public enum Status {
        SEARCHING,
        READY
    }

    // match maps this version's clock onto the original's.
    public record Version(Kind kind, String uri, String title, String source, BeatMatch.Result match,
                          AudioTrack track, long loadedAt) {
    }

    public record View(Status status, Kind active, Kind switching, List<Kind> available, int words) {
    }

    private static final int UNCENSORED_CANDIDATES = 4;
    private static final int MINUS_CANDIDATES = 5;
    private static final long MAX_TRACK_MS = 12 * 60_000L;
    private static final long DECODE_TIMEOUT_MS = 90_000L;
    private static final long LOAD_TIMEOUT_S = 30;
    private static final long SEARCH_TIMEOUT_S = 60;
    private static final long HEARD_FIRST_MS = 1_000;
    private static final int READ_AT_ONCE = 2;
    private static final long HEARD_PATIENCE_MS = 20_000;
    private static final long STALE_MS = 30 * 60_000L;
    private static final int CACHE_SIZE = 64;
    private static final long WARM_LEAD_MS = 500;
    private static final long WARM_SPAN_MS = 6_000;

    private final Main plugin;
    private final Map<Block, State> states = new ConcurrentHashMap<>();
    private final Map<String, Found> found = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Found> eldest) {
            return size() > CACHE_SIZE;
        }
    });
    // Searches and reads wait on work they hand to the same pool, so those two pools must grow.
    private final ExecutorService searcher = Executors.newCachedThreadPool(r -> thread(r, "cdisc-version-search"));
    private final ExecutorService reader = Executors.newCachedThreadPool(r -> thread(r, "cdisc-version-read"));
    private final ExecutorService checker = Executors.newFixedThreadPool(2, r -> thread(r, "cdisc-version-check"));

    private record Found(Version uncensored, Version minus, int words) {
    }

    // fromMs: where on the original's clock its buffer starts.
    private record Standby(AudioPlayer player, long fromMs) {
    }

    // The disc's names give the queries; ranking waits for the track's own length.
    private record Links(VariantFinder.Song song, List<VariantFinder.Hit> uncensored, List<VariantFinder.Hit> minus,
                         Map<String, AudioTrack> loaded) {

        static final Links NONE = new Links(null, List.of(), List.of(), Map.of());

        List<VariantFinder.Candidate> ranked(VariantFinder.Kind kind, long lengthMs, int limit) {
            if (song == null) return List.of();
            VariantFinder.Song known = new VariantFinder.Song(song.artist(), song.name(), lengthMs, song.uri());
            return VariantFinder.rank(known, kind, kind == VariantFinder.Kind.MINUS ? minus : uncensored, limit);
        }
    }

    private static final class State {
        final String key;
        final CompletableFuture<Links> links = new CompletableFuture<>();
        volatile AudioTrack original;
        volatile Status status = Status.SEARCHING;
        final Map<Kind, Version> versions = new EnumMap<>(Kind.class);
        volatile int words;
        volatile Kind active = Kind.ORIGINAL;
        volatile Kind switching;
        volatile Kind wanted;
        volatile boolean checked;
        private boolean delivered;
        private final Map<Kind, Standby> standby = new EnumMap<>(Kind.class);
        private final Set<Kind> reloading = EnumSet.noneOf(Kind.class);

        State(String key) {
            this.key = key;
        }

        synchronized boolean claimDelivery() {
            if (delivered) return false;
            delivered = true;
            return true;
        }

        synchronized Version version(Kind kind) {
            return versions.get(kind);
        }

        synchronized void put(Version version) {
            versions.put(version.kind(), version);
        }

        synchronized void remove(Kind kind) {
            versions.remove(kind);
        }

        synchronized Kind kindOf(String identifier) {
            AudioTrack playing = original;
            if (playing != null && Objects.equals(identifier, playing.getInfo().identifier)) return Kind.ORIGINAL;
            for (Version version : versions.values()) {
                if (Objects.equals(identifier, version.track().getInfo().identifier)) return version.kind();
            }
            return null;
        }

        synchronized List<Kind> available() {
            List<Kind> kinds = new ArrayList<>();
            kinds.add(Kind.ORIGINAL);
            for (Kind kind : List.of(Kind.UNCENSORED, Kind.MINUS)) {
                if (versions.containsKey(kind)) kinds.add(kind);
            }
            return kinds;
        }

        synchronized Standby standby(Kind kind) {
            return standby.get(kind);
        }

        synchronized void standBy(Kind kind, Standby next) {
            Standby old = standby.put(kind, next);
            if (old != null && old.player() != next.player()) old.player().destroy();
        }

        synchronized Standby take(Kind kind) {
            return standby.remove(kind);
        }

        synchronized void cool(Kind kind) {
            Standby old = standby.remove(kind);
            if (old != null) old.player().destroy();
        }

        synchronized void cool() {
            for (Standby held : standby.values()) held.player().destroy();
            standby.clear();
        }

        synchronized boolean startReload(Kind kind) {
            return reloading.add(kind);
        }

        synchronized void endReload(Kind kind) {
            reloading.remove(kind);
        }
    }

    public TrackVersions(Main plugin) {
        this.plugin = plugin;
    }

    private static Thread thread(Runnable r, String name) {
        Thread thread = new Thread(r, name);
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }

    public boolean isEnabled() {
        return plugin.cdiscConfig().isTrackVersionsEnabled();
    }

    public void shutdown() {
        searcher.shutdownNow();
        reader.shutdownNow();
        checker.shutdownNow();
        states.values().forEach(State::cool);
        states.clear();
    }

    // Called as the disc's own track starts loading: the links are searched alongside it, from the
    // names on the disc, and nothing the search does waits for or holds up that load.
    public void prepare(Block block, String resolved, String title, String author, String wanted) {
        if (!isEnabled() || resolved == null || BroadcastTrack.isAddress(resolved)) return;
        if (title == null || title.isBlank()) {
            replace(block, null);
            return;
        }
        Kind asked = Kind.fromKey(wanted) == Kind.ORIGINAL ? null : Kind.fromKey(wanted);
        String key = key(title, author);
        State known = states.get(block);
        if (known != null && known.original == null && known.key.equals(key)) {
            if (asked != null) known.wanted = asked;
            return;
        }

        State state = new State(key);
        state.wanted = asked;
        replace(block, state);
        VariantFinder.Song song = VariantFinder.Song.of(title, author, 0, canonical(resolved));
        searcher.execute(() -> search(block, state, song));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrackStart(TrackStartEvent event) {
        Block block = event.getJukebox();
        AudioTrack playing = playing(block);
        if (playing == null) return;
        State state = states.get(block);

        if (state != null && state.original != null) {
            Kind replayed = state.kindOf(playing.getInfo().identifier);
            if (replayed != null) {
                if (state.switching == null) state.active = replayed;
                return;
            }
            state = null;
        }
        if (!isEnabled() || !eligible(playing)) {
            replace(block, null);
            return;
        }
        if (state == null) {
            AudioTrackInfo info = playing.getInfo();
            State fresh = new State(key(info.title, info.author));
            replace(block, fresh);
            VariantFinder.Song song = VariantFinder.Song.of(info.title, info.author, info.length, canonical(info.uri));
            searcher.execute(() -> search(block, fresh, song));
            state = fresh;
        }
        state.original = playing;
        State started = state;
        checker.execute(() -> check(block, started));
    }

    // A disc just put in has its search running before the old session is stopped for it.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlaybackStop(PlaybackStopEvent event) {
        states.computeIfPresent(event.getJukebox(), (block, state) -> {
            if (state.original == null) return state;
            state.cool();
            return null;
        });
    }

    public void relocate(Block from, Block to) {
        State state = states.remove(from);
        if (state != null) states.put(to, state);
    }

    private static String key(String title, String author) {
        return VariantFinder.normalize(title) + "|" + VariantFinder.normalize(author);
    }

    private static String canonical(String uri) {
        String videoId = uri != null && uri.contains("youtu") ? SabrResolver.videoIdOf(uri) : null;
        return videoId == null ? uri : "https://www.youtube.com/watch?v=" + videoId;
    }

    private static boolean eligible(AudioTrack track) {
        AudioTrackInfo info = track.getInfo();
        return !info.isStream && !(track instanceof BroadcastTrack)
                && info.length > 0 && info.length <= MAX_TRACK_MS && !TrackLoader.isPreviewOnly(track);
    }

    private AudioTrack playing(Block block) {
        List<AudioSession> sessions = plugin.getAudioPlayerManager().getSessions(block);
        return sessions.isEmpty() ? null : sessions.get(0).getPlayer().getPlayingTrack();
    }

    public View view(Block block) {
        State state = states.get(block);
        if (state == null || state.original == null) return null;
        return new View(state.status, state.active, state.switching, state.available(), state.words);
    }

    // While someone has the player screen open, the other cuts wait buffered at the playing moment, so
    // the version button swaps at once; a jukebox nobody is looking at keeps no such player.
    public void keepWarm(Set<Block> watched) {
        for (Map.Entry<Block, State> entry : states.entrySet()) {
            if (isEnabled() && watched.contains(entry.getKey())) warm(entry.getKey(), entry.getValue());
            else entry.getValue().cool();
        }
    }

    private void warm(Block block, State state) {
        AudioTrack playing = playing(block);
        if (state.original == null || state.switching != null || playing == null) return;
        List<AudioSession> sessions = plugin.getAudioPlayerManager().getSessions(block);
        if (sessions.isEmpty()) return;
        long position = sessions.get(0).positionOf(playing);
        BeatMatch.Result clock = clockOf(block, playing);
        long here = clock == null ? position : clock.toOriginal(position);

        for (Kind kind : state.available()) {
            if (kind == state.active) {
                state.cool(kind);
                continue;
            }
            Version version = state.version(kind);
            if (version != null && System.currentTimeMillis() - version.loadedAt() > STALE_MS) {
                refreshStale(block, state, version);
                continue;
            }
            Standby held = state.standby(kind);
            if (held != null && here >= held.fromMs() && here - held.fromMs() < WARM_SPAN_MS) continue;

            long from = Math.max(0, here - WARM_LEAD_MS);
            long at = version == null ? from : Math.max(0, version.match().toCandidate(from));
            AudioTrack running = held == null ? null : held.player().getPlayingTrack();
            if (running != null) {
                running.setPosition(at);
                state.standBy(kind, new Standby(held.player(), from));
                continue;
            }
            AudioTrack track = version == null ? state.original.makeClone() : version.track().makeClone();
            track.setPosition(at);
            AudioPlayer player = plugin.getAudioPlayerManager().getTrackLoader().createPlayer();
            player.playTrack(track);
            state.standBy(kind, new Standby(player, from));
        }
    }

    private void refreshStale(Block block, State state, Version stale) {
        if (!state.startReload(stale.kind())) return;
        searcher.execute(() -> {
            Version fresh = reload(stale);
            state.endReload(stale.kind());
            if (states.get(block) != state || state.version(stale.kind()) != stale) return;
            if (fresh != null) {
                state.put(fresh);
                return;
            }
            state.remove(stale.kind());
            forget(state);
            refresh(block);
        });
    }

    private void forget(State state) {
        AudioTrack original = state.original;
        if (original != null) found.remove(original.getInfo().identifier);
    }

    private void replace(Block block, State next) {
        State old = next == null ? states.remove(block) : states.put(block, next);
        if (old != null && old != next) old.cool();
    }

    private void deliver(Block block, State state) {
        Kind wanted = state.wanted;
        if (wanted == null || states.get(block) != state) return;
        Version version = state.version(wanted);
        if (version == null && state.status != Status.READY) return;
        if (!state.claimDelivery()) return;
        Tasks.region(plugin, block, () -> {
            if (states.get(block) != state) return;
            if (version == null) {
                if (state.checked) missing(block, wanted);
                return;
            }
            switchTo(block, state, wanted, switched -> {
                if (!switched) tell(block, "gui.versions.failed", null);
            });
        });
    }

    private void missing(Block block, Kind kind) {
        plugin.getAudioPlayerManager().forgetWantedVersion(block);
        tell(block, "gui.versions.missing", kind);
    }

    private void tell(Block block, String key, Kind kind) {
        plugin.getAudioPlayerManager().tellListeners(block, player -> "§e" + plugin.getMessageManager().get(player, key,
                kind == null ? "" : plugin.getPlayerActions().versionName(player, kind)));
    }

    public BeatMatch.Result clockOf(Block block, AudioTrack playing) {
        State state = states.get(block);
        if (state == null || playing == null || state.active == Kind.ORIGINAL) return null;
        Version version = state.version(state.active);
        if (version == null || !Objects.equals(version.track().getInfo().identifier, playing.getInfo().identifier)) {
            return null;
        }
        return version.match();
    }

    public AudioTrackInfo originalInfo(Block block) {
        State state = states.get(block);
        AudioTrack original = state == null ? null : state.original;
        return original == null ? null : original.getInfo();
    }

    public enum Switch {
        STARTED,
        NOT_READY,
        NOTHING_ELSE,
        BUSY
    }

    public Switch cycle(Block block, boolean forward, Consumer<Boolean> finished) {
        State state = states.get(block);
        if (state == null || state.original == null) return Switch.NOT_READY;
        if (state.switching != null) return Switch.BUSY;
        List<Kind> kinds = state.available();
        if (kinds.size() < 2) return state.status == Status.SEARCHING ? Switch.NOT_READY : Switch.NOTHING_ELSE;
        int at = kinds.indexOf(state.active);
        Kind target = kinds.get(Math.floorMod(at + (forward ? 1 : -1), kinds.size()));
        return switchTo(block, state, target, finished) ? Switch.STARTED : Switch.NOTHING_ELSE;
    }

    private boolean switchTo(Block block, State state, Kind target, Consumer<Boolean> finished) {
        Kind from = state.active;
        Version version = state.version(target);
        if (target != Kind.ORIGINAL && version == null) return false;
        if (version != null && System.currentTimeMillis() - version.loadedAt() > STALE_MS) {
            state.switching = target;
            searcher.execute(() -> {
                Version fresh = reload(version);
                state.switching = null;
                if (states.get(block) != state) return;
                if (fresh == null) {
                    state.remove(target);
                    forget(state);
                    Tasks.region(plugin, block, () -> finished.accept(false));
                    return;
                }
                state.put(fresh);
                Tasks.region(plugin, block, () -> switchTo(block, state, target, finished));
            });
            return true;
        }

        Version leaving = state.version(from);
        LongUnaryOperator map = t -> {
            long original = from == Kind.ORIGINAL || leaving == null ? t : leaving.match().toOriginal(t);
            return Math.max(0, target == Kind.ORIGINAL ? original : version.match().toCandidate(original));
        };
        Runnable done = () -> {
            state.switching = null;
            state.active = target;
            Tasks.region(plugin, block, () -> finished.accept(true));
        };
        Runnable failed = () -> {
            state.switching = null;
            if (target != Kind.ORIGINAL) state.remove(target);
            Tasks.region(plugin, block, () -> finished.accept(false));
        };
        state.switching = target;
        Standby ready = state.take(target);
        boolean started = ready != null
                ? plugin.getAudioPlayerManager().swapVersion(block, ready.player(), map, done, failed)
                : plugin.getAudioPlayerManager().swapVersion(block,
                target == Kind.ORIGINAL ? state.original.makeClone() : version.track().makeClone(), map, done, failed);
        if (!started) {
            state.switching = null;
            if (ready != null) ready.player().destroy();
        }
        return started;
    }

    public void versionFailed(Block block, AudioTrack failed) {
        State state = states.get(block);
        if (state == null || state.original == null || state.active == Kind.ORIGINAL) return;
        Kind broken = state.active;
        Version version = state.version(broken);
        long at = version == null ? 0 : version.match().toOriginal(failed.getPosition());
        state.remove(broken);
        state.active = Kind.ORIGINAL;
        AudioTrack track = state.original.makeClone();
        track.setPosition(Math.max(0, at));
        plugin.getAudioPlayerManager().swapVersion(block, track, t -> Math.max(0, at), () -> { }, () -> { });
    }

    private void search(Block block, State state, VariantFinder.Song song) {
        Map<String, AudioTrack> loaded = new ConcurrentHashMap<>();
        VariantFinder finder = new VariantFinder(sources(loaded), searcher);
        List<VariantFinder.Hit> uncensored = List.of();
        List<VariantFinder.Hit> minus = List.of();
        try {
            if (states.get(block) == state) {
                CompletableFuture<List<VariantFinder.Hit>> clean = CompletableFuture.supplyAsync(
                        () -> gather(finder, song, VariantFinder.Kind.UNCENSORED), searcher);
                minus = finder.gather(song, VariantFinder.Kind.MINUS);
                uncensored = clean.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Searching for other versions of \"" + song.name() + "\" failed", e);
        }
        state.links.complete(new Links(song, uncensored, minus, loaded));
    }

    private static List<VariantFinder.Hit> gather(VariantFinder finder, VariantFinder.Song song, VariantFinder.Kind kind) {
        try {
            return finder.gather(song, kind);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    // Reading the candidates competes with the track's own first second, so it waits until that
    // has been heard; the minus and the uncensored cut are then read side by side.
    private void check(Block block, State state) {
        AudioTrack playing = state.original;
        AudioTrackInfo info = playing.getInfo();
        Found cached = found.get(info.identifier);
        if (cached != null) {
            apply(block, state, cached);
            return;
        }
        try {
            Links links;
            try {
                links = state.links.get(SEARCH_TIMEOUT_S, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                links = Links.NONE;
            }
            if (state.wanted != null ? states.get(block) != state : !heardFirstSeconds(block, state)) return;
            // Read beside the candidates, not before them: each one waits for it only to compare.
            CompletableFuture<PcmDigest.Digest> original = CompletableFuture.supplyAsync(() -> digest(playing), reader);
            CompletableFuture<VocalDiff.Reference> reference = original.thenApplyAsync(d -> VocalDiff.reference(d.pcm()), reader);
            Links searched = links;
            CompletableFuture<Version> minusLane = CompletableFuture.supplyAsync(
                    () -> findMinus(block, state, original, searched), reader);

            List<VariantJudge.Heard> heard = new ArrayList<>();
            Map<VariantJudge.Heard, AudioTrack> tracks = new LinkedHashMap<>();
            List<VariantFinder.Candidate> candidates = searched.ranked(VariantFinder.Kind.UNCENSORED, info.length,
                    UNCENSORED_CANDIDATES);
            for (int from = 0; from < candidates.size() && states.get(block) == state; from += READ_AT_ONCE) {
                List<CompletableFuture<Heard>> batch = new ArrayList<>();
                for (VariantFinder.Candidate candidate : candidates.subList(from, Math.min(candidates.size(), from + READ_AT_ONCE))) {
                    batch.add(CompletableFuture.supplyAsync(
                            () -> listen(original, reference, playing, candidate.hit(), searched.loaded()), reader));
                }
                for (CompletableFuture<Heard> next : batch) {
                    Heard sample = next.join();
                    if (sample == null) continue;
                    heard.add(sample.heard());
                    tracks.put(sample.heard(), sample.track());
                }
                VariantJudge.Verdict verdict = VariantJudge.judge(heard, info.length);
                if (verdict.censored() ? verdict.uncensored().match().aligned()
                        : heard.stream().anyMatch(h -> VariantJudge.cleanCopy(h, heard))) break;
            }
            VariantJudge.Verdict verdict = VariantJudge.judge(heard, info.length);
            VariantJudge.Heard pick = verdict.censored() ? verdict.uncensored() : null;
            Version uncensored = pick == null ? null : version(Kind.UNCENSORED, pick.hit(), pick.match(), tracks.get(pick));
            if (states.get(block) == state) {
                state.words = verdict.words();
                if (uncensored != null) {
                    state.put(uncensored);
                    refresh(block);
                    deliver(block, state);
                }
            }

            Version minus = minusLane.join();
            if (states.get(block) != state) return;
            found.put(info.identifier, new Found(uncensored, minus, verdict.words()));
            state.checked = true;
            if (plugin.cdiscConfig().isDebug()) {
                plugin.getLogger().info("Versions of \"" + info.title + "\": "
                        + (verdict.censored() ? verdict.words() + " censored words" : "not censored")
                        + (uncensored == null ? ", no uncensored cut" : ", uncensored at " + uncensored.uri())
                        + (minus == null ? ", no instrumental" : ", instrumental at " + minus.uri()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Checking other versions of \"" + info.title + "\" failed", e);
        }
        state.status = Status.READY;
        refresh(block);
        deliver(block, state);
    }

    private Version findMinus(Block block, State state, CompletableFuture<PcmDigest.Digest> original, Links links) {
        long length = state.original.getInfo().length;
        List<VariantFinder.Candidate> candidates = links.ranked(VariantFinder.Kind.MINUS, length, MINUS_CANDIDATES);
        for (int from = 0; from < candidates.size() && states.get(block) == state; from += READ_AT_ONCE) {
            List<CompletableFuture<Heard>> batch = new ArrayList<>();
            for (VariantFinder.Candidate candidate : candidates.subList(from, Math.min(candidates.size(), from + READ_AT_ONCE))) {
                batch.add(CompletableFuture.supplyAsync(
                        () -> listen(original, null, state.original, candidate.hit(), links.loaded()), reader));
            }
            for (CompletableFuture<Heard> next : batch) {
                Heard sample = next.join();
                if (sample == null || !sample.heard().match().aligned() || !sample.vocalsOut() || states.get(block) != state) {
                    continue;
                }
                Version minus = version(Kind.MINUS, sample.heard().hit(), sample.heard().match(), sample.track());
                state.put(minus);
                refresh(block);
                deliver(block, state);
                return minus;
            }
        }
        return null;
    }

    private boolean heardFirstSeconds(Block block, State state) throws InterruptedException {
        AudioTrack playing = state.original;
        long from = playing.getPosition();
        long deadline = System.currentTimeMillis() + HEARD_PATIENCE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (states.get(block) != state) return false;
            if (playing.getPosition() - from >= HEARD_FIRST_MS) return true;
            Thread.sleep(250);
        }
        return states.get(block) == state;
    }

    private static PcmDigest.Digest digest(AudioTrack track) {
        try {
            return PcmDigest.read(track.makeClone(), MAX_TRACK_MS, DECODE_TIMEOUT_MS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    private void apply(Block block, State state, Found cached) {
        if (cached.uncensored() != null) state.put(cached.uncensored());
        if (cached.minus() != null) state.put(cached.minus());
        state.words = cached.words();
        state.status = Status.READY;
        state.checked = true;
        refresh(block);
        deliver(block, state);
    }

    // vocalsOut: the sound, not the title, says this cut is the song with its vocals taken out.
    private record Heard(VariantJudge.Heard heard, AudioTrack track, boolean vocalsOut) {
    }

    // reference null: only the beat is matched, the vocals are not compared.
    private Heard listen(CompletableFuture<PcmDigest.Digest> original, CompletableFuture<VocalDiff.Reference> reference,
                         AudioTrack playing, VariantFinder.Hit hit, Map<String, AudioTrack> loaded) {
        if (canonical(hit.uri()).equals(canonical(playing.getInfo().uri))) return null;
        long length = playing.getInfo().length;
        if (hit.durationMs() > 0 && !VariantFinder.lengthFits(length, hit.durationMs())) return null;
        try {
            AudioTrack track = loaded.get(hit.uri());
            if (track == null) track = load(hit.uri());
            if (track == null || TrackLoader.isPreviewOnly(track)) return null;
            PcmDigest.Digest heard = PcmDigest.read(track.makeClone(), MAX_TRACK_MS, DECODE_TIMEOUT_MS);
            PcmDigest.Digest source = original.join();
            float[] pcm = heard.pcm();
            BeatMatch.Result match = BeatMatch.compare(source.pcm(), pcm);
            if (!match.sameSong()) return null;
            List<VocalDiff.Spot> spots = reference == null ? List.of()
                    : VocalDiff.spots(reference.join(), pcm, match.offsetMs(), match.speed());
            return new Heard(new VariantJudge.Heard(hit, match, spots), track, PcmDigest.vocalsTakenOut(source, heard));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Could not compare " + hit.uri(), e);
            return null;
        }
    }

    private Version version(Kind kind, VariantFinder.Hit hit, BeatMatch.Result match, AudioTrack track) {
        return new Version(kind, hit.uri(), hit.title(), hit.source(), match, track, System.currentTimeMillis());
    }

    private Version reload(Version stale) {
        try {
            AudioTrack track = load(stale.uri());
            return track == null ? null
                    : new Version(stale.kind(), stale.uri(), stale.title(), stale.source(), stale.match(), track,
                    System.currentTimeMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private AudioTrack load(String uri) throws InterruptedException {
        TrackLoader loader = plugin.getAudioPlayerManager().getTrackLoader();
        String resolved = loader.resolveQuery(uri);
        if (resolved == null) return null;
        CompletableFuture<AudioTrack> result = new CompletableFuture<>();
        loader.load(resolved, null, null, null, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                result.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                result.complete(playlist.getTracks().isEmpty() ? null : playlist.getTracks().get(0));
            }

            @Override
            public void noMatches() {
                result.complete(null);
            }

            @Override
            public void loadFailed(LoadException exception) {
                result.complete(null);
            }
        });
        try {
            return result.get(LOAD_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }

    private List<VariantFinder.Source> sources(Map<String, AudioTrack> loaded) {
        TrackLoader loader = plugin.getAudioPlayerManager().getTrackLoader();
        List<VariantFinder.Source> sources = new ArrayList<>();
        sources.add(new VariantFinder.Source() {
            @Override
            public String name() {
                return VariantFinder.YOUTUBE;
            }

            @Override
            public List<VariantFinder.Hit> search(String query) throws IOException, InterruptedException {
                List<VariantFinder.Hit> hits = new ArrayList<>();
                for (YouTubeSearch.Result result : loader.searchYoutube(query, 10)) {
                    hits.add(new VariantFinder.Hit(name(), "https://www.youtube.com/watch?v=" + result.videoId(),
                            result.title(), result.channel(), result.durationSeconds() * 1000));
                }
                return hits;
            }
        });
        sources.add(new VariantFinder.Source() {
            @Override
            public String name() {
                return VariantFinder.SOUNDCLOUD;
            }

            @Override
            public List<VariantFinder.Hit> search(String query) {
                List<VariantFinder.Hit> hits = new ArrayList<>();
                AudioItem item = loader.searchSoundCloud(query);
                if (!(item instanceof AudioPlaylist playlist)) return hits;
                for (AudioTrack track : playlist.getTracks()) {
                    if (TrackLoader.isPreviewOnly(track)) continue;
                    AudioTrackInfo info = track.getInfo();
                    loaded.put(info.uri, track);
                    hits.add(new VariantFinder.Hit(name(), info.uri, info.title, info.author, info.length));
                }
                return hits;
            }
        });
        return sources;
    }

    private void refresh(Block block) {
        Tasks.region(plugin, block, () -> plugin.getPlayerGuiManager().refreshViewers(block));
    }
}
