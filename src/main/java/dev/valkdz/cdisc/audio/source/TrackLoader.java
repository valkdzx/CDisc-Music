package dev.valkdz.cdisc.audio.source;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioLoadResultHandler;
import dev.valkdz.cdisc.audio.player.AudioPlayer;
import dev.valkdz.cdisc.audio.player.AudioPlaylist;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.HttpAudioTrack;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.audio.source.music.BackendMusicSourceManager;
import dev.valkdz.cdisc.audio.source.music.VkMusicSourceManager;
import dev.valkdz.cdisc.audio.source.music.YandexMusicSourceManager;
import dev.valkdz.cdisc.audio.source.soundcloud.SoundCloudSourceManager;
import dev.valkdz.cdisc.audio.source.spotify.SpotifySourceManager;
import dev.valkdz.cdisc.audio.source.youtube.CustomYoutubeApiResolver;
import dev.valkdz.cdisc.config.Config;
import dev.valkdz.cdisc.feature.local.LocalMusicLibrary;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrackLoader {

    private static final String BACKEND_BASE = "https://2281273.xyz/";
    private static final Pattern TWITCH_URL = Pattern.compile(
            "^https?://(?:www\\.|go\\.|m\\.)?twitch\\.tv/([A-Za-z0-9_]{2,25})/?(?:\\?.*)?$");

    private final Main plugin;
    private final List<AudioSourceManager> sources = new CopyOnWriteArrayList<>();

    private static final String WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private CustomYoutubeApiResolver customApiResolver;
    private dev.valkdz.cdisc.audio.source.youtube.SabrResolver sabrResolver;
    private dev.valkdz.cdisc.audio.source.spotify.SpotifyBridge spotifyBridge;
    private dev.valkdz.cdisc.audio.source.youtube.YouTubeSearch youtubeSearch;

    private volatile CustomYoutubeApiResolver ageGateApi;

    private final ExecutorService resolveExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cdisc-yt-resolve");
        t.setDaemon(true);
        return t;
    });

    private static final long ROUTE_TTL_MS = 6L * 60 * 60 * 1000;

    public record Route(String contentType, long lengthMs, boolean live, Boolean allowedHere,
                        long at, String plan) {}

    private final Map<String, Route> routes = new ConcurrentHashMap<>();

    private Route route(String videoId) {
        Route route = routes.get(videoId);
        if (route == null) return null;
        if (System.currentTimeMillis() - route.at() > ROUTE_TTL_MS) {
            routes.remove(videoId);
            return null;
        }
        return route;
    }

    private void remember(String videoId, CustomYoutubeApiResolver.Info info) {
        String region = sabrResolver == null ? null : sabrResolver.region();
        Route known = route(videoId);

        // A refusal we actually got from here outranks the country list, which the
        // backend reads from its own address.
        Boolean allowed = known != null && Boolean.FALSE.equals(known.allowedHere())
                ? Boolean.FALSE
                : (info.playable() ? info.allowedIn(region) : Boolean.FALSE);

        routes.put(videoId, new Route(info.contentType(), info.lengthMs(), info.live(),
                allowed, System.currentTimeMillis(), known == null ? null : known.plan()));
    }

    private final java.util.Set<String> preferBackend = ConcurrentHashMap.newKeySet();

    private static final int DIRECT_MISSES_TO_PAUSE = 3;
    private static final long DIRECT_PAUSE_MS = 10 * 60 * 1000L;
    private final java.util.concurrent.atomic.AtomicInteger directMisses =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile long directPausedUntil;

    public CompletableFuture<List<CustomYoutubeApiResolver.Hit>> searchBackend(String query, int limit) {
        if (customApiResolver == null || isBlank(query)) {
            return CompletableFuture.completedFuture(List.of());
        }
        return customApiResolver.searchAsync(query, limit);
    }

    public void routeThroughBackend(String videoId) {
        if (videoId != null) preferBackend.add(videoId);
    }

    public static String searchTextOf(String resolved) {
        int colon = resolved == null ? -1 : resolved.indexOf(':');
        return colon < 0 ? resolved : resolved.substring(colon + 1);
    }

    private record Blocked(String title, String author) {}

    private final Map<String, Blocked> blockedByRegion = new ConcurrentHashMap<>();

    private static final long REUPLOAD_SLACK_SECONDS = 3;
    private static final String REUPLOAD_PLAN = "reupload";

    private final Map<String, String> reuploads = new ConcurrentHashMap<>();

    public boolean reuploaded(String resolved) {
        String videoId = dev.valkdz.cdisc.audio.source.youtube.SabrResolver.videoIdOf(resolved);
        return videoId != null && reuploads.containsKey(videoId);
    }

    private AudioTrack reupload(String videoId, String title, String author) {
        if (videoId == null || youtubeSearch == null || isBlank(title)) return null;

        String artist = author == null ? null : author.replaceFirst("\\s*-\\s*Topic$", "");
        String known = reuploads.get(videoId);
        if (known != null) {
            AudioTrack again = attemptDirect(known, title, artist, true, false).track();
            if (again != null) return again;
            reuploads.remove(videoId);
        }

        try {
            var results = youtubeSearch.search(isBlank(artist) ? title : title + " " + artist,
                    sabrResolver.visitorDataOrNull(), 10);

            long length = results.stream()
                    .filter(found -> videoId.equals(found.videoId()))
                    .mapToLong(dev.valkdz.cdisc.audio.source.youtube.YouTubeSearch.Result::durationSeconds)
                    .findFirst().orElse(0);
            if (length <= 0) {
                Route route = route(videoId);
                length = route == null ? 0 : route.lengthMs() / 1000;
            }
            // Without the original's length a speed-up or a one-hour loop would pass as the track.
            if (length <= 0) return null;

            for (var found : results) {
                if (videoId.equals(found.videoId())
                        || Math.abs(found.durationSeconds() - length) > REUPLOAD_SLACK_SECONDS) continue;

                AudioTrack track = attemptDirect(found.videoId(), title, artist, true, false).track();
                if (track != null) {
                    reuploads.put(videoId, found.videoId());
                    Bukkit.getLogger().info("[CDisc] \"" + title + "\" needs a YouTube account; playing "
                            + found.videoId() + ", another upload of the same length, instead.");
                    return track;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Bukkit.getLogger().warning("[CDisc] No other upload could be found: " + e.getMessage());
        }
        return null;
    }

    public boolean blockedByRegion(String resolved) {
        String videoId = dev.valkdz.cdisc.audio.source.youtube.SabrResolver.videoIdOf(resolved);
        return videoId != null && blockedByRegion.containsKey(videoId);
    }

    public boolean wasSubstituted(String resolved, AudioTrack track) {
        if (track == null) return false;

        String asked = dev.valkdz.cdisc.audio.source.youtube.SabrResolver.videoIdOf(resolved);
        String got = dev.valkdz.cdisc.audio.source.youtube.SabrResolver.videoIdOf(track.getInfo().identifier);
        return asked != null && got != null && !asked.equals(got);
    }

    private AudioTrack substitute(String videoId) {
        Blocked blocked = blockedByRegion.get(videoId);
        if (blocked == null || youtubeSearch == null || isBlank(blocked.title())) return null;

        String query = isBlank(blocked.author())
                ? blocked.title() : blocked.title() + " " + blocked.author();

        try {
            var results = youtubeSearch.search(query,
                    sabrResolver == null ? null : sabrResolver.visitorDataOrNull(), 8);

            for (var found : results) {
                if (videoId.equals(found.videoId())) continue;

                AudioTrack track = attemptDirect(found.videoId(), blocked.title(),
                        blocked.author(), false).track();
                if (track != null) {
                    Bukkit.getLogger().info("[CDisc] \"" + blocked.title() + "\" is not offered in "
                            + (sabrResolver == null ? "this region" : sabrResolver.region())
                            + "; playing " + found.videoId() + " instead.");
                    return track;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Bukkit.getLogger().warning("[CDisc] No regional replacement could be found: "
                    + e.getMessage());
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void markBlocked(String videoId) {
        if (videoId == null) return;

        Route known = route(videoId);
        routes.put(videoId, new Route(
                known == null ? null : known.contentType(),
                known == null ? 0 : known.lengthMs(),
                known != null && known.live(),
                Boolean.FALSE,
                System.currentTimeMillis(),
                known == null ? null : known.plan()));
    }

    public void remember(dev.valkdz.cdisc.disc.ItemUtils.DiscData data) {
        if (data == null || data.hint() == null || !data.hint().fresh()) return;

        String videoId = dev.valkdz.cdisc.audio.source.youtube.SabrResolver.videoIdOf(data.query());
        if (videoId == null || routes.containsKey(videoId)) return;

        var hint = data.hint();
        routes.put(videoId, new Route(hint.contentType(), hint.lengthMs(), hint.live(),
                hint.allowedHere(), hint.writtenAt(), hint.plan()));
    }

    public dev.valkdz.cdisc.disc.ItemUtils.Hint hintFor(String videoId) {
        Route route = videoId == null ? null : route(videoId);
        return route == null ? null : new dev.valkdz.cdisc.disc.ItemUtils.Hint(
                route.contentType(), route.lengthMs(), route.live(), route.allowedHere(),
                route.at(), route.plan());
    }

    private final Map<String, CompletableFuture<CustomYoutubeApiResolver.Info>> inflight =
            new ConcurrentHashMap<>();

    private CompletableFuture<CustomYoutubeApiResolver.Info> infoFor(String videoId) {
        if (videoId == null || customApiResolver == null) {
            return CompletableFuture.completedFuture(null);
        }

        return inflight.computeIfAbsent(videoId, id ->
                customApiResolver.infoAsync(id).whenComplete((info, ex) -> {
                    inflight.remove(id);
                    if (info != null) remember(id, info);
                }));
    }

    public CompletableFuture<dev.valkdz.cdisc.disc.ItemUtils.Hint> hintLater(String videoId,
                                                                            AudioTrack track) {
        dev.valkdz.cdisc.disc.ItemUtils.Hint local = localHint(videoId, track);

        if (local != null && local.contentType() != null) {
            if (videoId != null) {
                routes.put(videoId, new Route(local.contentType(), local.lengthMs(), local.live(),
                        local.allowedHere(), local.writtenAt(), local.plan()));
            }
            return CompletableFuture.completedFuture(local);
        }

        if (videoId == null) return CompletableFuture.completedFuture(local);
        if (route(videoId) != null) return CompletableFuture.completedFuture(hintFor(videoId));

        return infoFor(videoId)
                .thenApply(ignored -> {
                    dev.valkdz.cdisc.disc.ItemUtils.Hint asked = hintFor(videoId);
                    return asked != null ? asked : local;
                })
                .completeOnTimeout(local, 3, TimeUnit.SECONDS);
    }

    private dev.valkdz.cdisc.disc.ItemUtils.Hint localHint(String videoId, AudioTrack track) {
        if (track == null) return null;

        Route known = videoId == null ? null : route(videoId);
        long length = track.getInfo().length;

        return new dev.valkdz.cdisc.disc.ItemUtils.Hint(
                contentTypeOf(track),
                length == AudioTrackInfo.UNKNOWN_LENGTH ? 0 : length,
                track.getInfo().isStream,
                readDirectly(track) ? Boolean.TRUE : (known == null ? null : known.allowedHere()),
                System.currentTimeMillis(),
                planFor(videoId, known));
    }

    private String planFor(String videoId, Route known) {
        if (videoId != null && reuploads.containsKey(videoId)) return REUPLOAD_PLAN;
        String learned = sabrResolver == null ? null : sabrResolver.planOf(videoId);
        return learned != null ? learned : (known == null ? null : known.plan());
    }

    private static boolean readDirectly(AudioTrack track) {
        return track instanceof dev.valkdz.cdisc.audio.source.youtube.DirectAudioTrack
                || track instanceof dev.valkdz.cdisc.audio.source.youtube.SabrAudioTrack
                || track instanceof dev.valkdz.cdisc.audio.source.youtube.LiveAudioTrack;
    }

    private static String contentTypeOf(AudioTrack track) {
        if (track instanceof dev.valkdz.cdisc.audio.source.youtube.DirectAudioTrack direct) {
            return bareType(direct.mimeType());
        }
        if (track instanceof dev.valkdz.cdisc.audio.source.youtube.SabrAudioTrack sabr) {
            return bareType(sabr.mimeType());
        }
        if (track instanceof dev.valkdz.cdisc.audio.source.youtube.LiveAudioTrack) {
            return "audio/mp4";
        }
        if (track instanceof HttpAudioTrack http) {
            return bareType(http.mimeType());
        }
        return null;
    }

    private static String bareType(String mimeType) {
        if (mimeType == null) return null;
        int parameters = mimeType.indexOf(';');
        return parameters < 0 ? mimeType.trim() : mimeType.substring(0, parameters).trim();
    }

    public TrackLoader(Main plugin) {
        this.plugin = plugin;
        registerSources();
    }

    private void registerSources() {
        Config config = plugin.cdiscConfig();
        sources.clear();

        if (config.isYoutubeEnabled()) {
            String rcUrl = config.getRemoteCipherServerUrl();
            String rcPass = config.getRemoteCipherServerPassword();

            boolean customApi = config.getYoutubeCustomApi();
            boolean useProxy = config.isYoutubeProxyEnabled();
            customApiResolver = customApi ? new CustomYoutubeApiResolver(BACKEND_BASE, useProxy) : null;
            if (customApiResolver != null) {
                customApiResolver.setObserver(info -> remember(info.videoId(), info));
            }

            sabrResolver = new dev.valkdz.cdisc.audio.source.youtube.SabrResolver(
                    this::sabrIdentity, rcUrl, rcPass, config.isYoutubeSabrEnabled());
            sabrResolver.warmUp();

            java.net.http.HttpClient bridgeHttp = dev.valkdz.cdisc.util.NetProxy.apply(java.net.http.HttpClient.newBuilder())
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();

            youtubeSearch = new dev.valkdz.cdisc.audio.source.youtube.YouTubeSearch(
                    bridgeHttp, config.getYoutubeWebClientVersion());

            spotifyBridge = new dev.valkdz.cdisc.audio.source.spotify.SpotifyBridge(
                    bridgeHttp,
                    youtubeSearch,
                    config::getSpotifyClientId,
                    config::getSpotifyClientSecret,
                    config::getPoTokenBackendUrl,
                    config::getPoTokenBackendPassword,
                    () -> sabrResolver == null ? null : sabrResolver.visitorDataOrNull());

            sources.add(new dev.valkdz.cdisc.audio.source.youtube.YouTubeSourceManager(youtubeSearch,
                    new dev.valkdz.cdisc.audio.source.youtube.YouTubePlaylist(config.getYoutubeWebClientVersion()),
                    () -> sabrResolver == null ? null : sabrResolver.visitorDataOrNull(),
                    info -> youtubeNow(info.uri, info.title, info.author)));
        }

        if (config.isTiktokEnabled()) {
            sources.add(new dev.valkdz.cdisc.audio.source.tiktok.TikTokSourceManager());
        }

        if (config.isSoundcloudEnabled()) {
            if (config.isSoundcloudProxyEnabled()) {
                sources.add(new dev.valkdz.cdisc.audio.source.soundcloud.SoundCloudProxySourceManager(
                        config.getSoundcloudProxyUrl()));
            }
            sources.add(new SoundCloudSourceManager());
        }

        if (config.isSpotifyEnabled() && spotifyBridge != null) {
            sources.add(new SpotifySourceManager(spotifyBridge, info -> spotifyNow(info.identifier)));
        }

        if (config.isYandexMusicEnabled()) {
            String token = config.getYandexMusicAccessToken();
            registerBackendMusic(BackendMusicSourceManager.Service.YANDEX,
                    config.getYandexMusicBackendUrl(), !token.isEmpty());
            if (!token.isEmpty()) sources.add(new YandexMusicSourceManager(token));
        }

        if (config.isVkMusicEnabled()) {
            String token = config.getVkMusicUserToken();
            registerBackendMusic(BackendMusicSourceManager.Service.VK,
                    config.getVkMusicBackendUrl(), !token.isEmpty());
            if (!token.isEmpty()) sources.add(new VkMusicSourceManager(token));
        }

        if (config.isTwitchEnabled()) {
            sources.add(new dev.valkdz.cdisc.audio.source.twitch.TwitchSourceManager());
        }

        if (config.isHttpEnabled()) {
            sources.add(new ScopedHttpAudioSourceManager());
        } else if (config.isDiscordEnabled()) {
            sources.add(new ScopedHttpAudioSourceManager(dev.valkdz.cdisc.audio.source.DiscordSource.URL_PREFIXES));
        }

        if (config.isLocalEnabled()) {
            sources.add(new LocalFileSourceManager());
        }
    }

    // Must be registered before the token source of the same service: sources are asked
    // in order, and the token source only sees what this one declines.
    private void registerBackendMusic(BackendMusicSourceManager.Service service, String url,
                                      boolean hasToken) {
        if (url.isEmpty()) return;
        sources.add(new BackendMusicSourceManager(service, url, hasToken, plugin.getLogger()));
    }

    public void reloadSources() {
        registerSources();
    }

    public AudioPlayer createPlayer() {
        return new AudioPlayer();
    }

    public boolean hasCustomApi() {
        return customApiResolver != null;
    }

    public void loadItem(String resolved, AudioLoadResultHandler handler) {
        if (loadBroadcast(resolved, null, null, handler)) return;
        if (interceptSpotifyCollection(resolved, handler)) return;
        if (interceptSpotify(resolved, handler)) return;
        if (isYoutubeIdentifier(resolved) && !isSearch(resolved) && !resolved.contains("list=")) {
            loadSmart(resolved, handler);
            return;
        }
        loadFromSources(resolved, handler);
    }

    private void loadFromSources(String identifier, AudioLoadResultHandler handler) {
        resolveExecutor.submit(() -> {
            AudioItem item = null;
            try {
                for (AudioSourceManager source : sources) {
                    item = source.loadItem(identifier);
                    if (item != null) break;
                }
            } catch (LoadException e) {
                handler.loadFailed(e);
                return;
            } catch (RuntimeException e) {
                handler.loadFailed(new LoadException("Something broke while loading this track: " + e, e));
                return;
            }
            invokeHandler(item, handler);
        });
    }

    private AudioTrack youtubeNow(String url, String title, String author) {
        AudioTrack direct = attemptDirect(url, title, author, false).track();
        if (direct != null) return direct;
        CustomYoutubeApiResolver api = customApiResolver;
        return api == null ? null : api.resolve(url);
    }

    private AudioTrack spotifyNow(String trackId) throws Exception {
        dev.valkdz.cdisc.audio.source.spotify.SpotifyBridge.Match match = spotifyBridge.resolve(trackId);
        return youtubeNow(match.youtubeUrl(), match.title(), match.artist());
    }

    private boolean interceptSpotifyCollection(String resolved, AudioLoadResultHandler handler) {
        String collection = dev.valkdz.cdisc.audio.source.spotify.SpotifyBridge.collectionOf(resolved);
        if (collection == null || spotifyBridge == null || !spotifyBridge.isUsable()) return false;
        if (!plugin.cdiscConfig().isSpotifyEnabled()) return false;

        resolveExecutor.submit(() -> {
            try {
                var read = spotifyBridge.readCollection(collection);
                List<AudioTrack> tracks = new ArrayList<>();
                for (var entry : read.entries()) {
                    tracks.add(new dev.valkdz.cdisc.audio.source.spotify.SpotifyEntryTrack(entry,
                            info -> spotifyNow(info.identifier)));
                }
                handler.playlistLoaded(new AudioPlaylist(read.name(), tracks, null, false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                handler.loadFailed(new LoadException("Could not read the Spotify "
                        + collection + ": " + e.getMessage(), e));
            }
        });
        return true;
    }

    public String resolvePlaylistQuery(String query) {
        String q = query == null ? "" : query.trim();
        String list = q.contains("youtube.com") || q.contains("youtu.be") ? queryParam(q, "list") : null;
        if (list == null || list.isEmpty()) return resolveQuery(query);

        String video = normalizeYoutubeUrl(q);
        return video.contains("watch?v=")
                ? video + "&list=" + list
                : "https://www.youtube.com/playlist?list=" + list;
    }

    // A Spotify disc is written from Spotify's own names; the YouTube match is found
    // meanwhile, so the first play does not wait for it either.
    public void loadForDisc(String resolved, AudioLoadResultHandler handler) {
        String trackId = dev.valkdz.cdisc.audio.source.spotify.SpotifyBridge.trackIdOf(resolved);
        if (trackId == null || spotifyBridge == null || !spotifyBridge.isUsable()) {
            loadItem(resolved, handler);
            return;
        }

        resolveExecutor.submit(() -> {
            dev.valkdz.cdisc.audio.source.spotify.SpotifyBridge.Entry entry;
            try {
                entry = spotifyBridge.entry(trackId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                loadItem(resolved, handler);
                return;
            }
            handler.trackLoaded(new dev.valkdz.cdisc.audio.source.spotify.SpotifyEntryTrack(entry,
                    info -> spotifyNow(info.identifier)));
            try {
                spotifyBridge.resolve(trackId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Bukkit.getLogger().warning("[CDisc] \"" + entry.title() + "\" has no match on YouTube yet ("
                        + e.getMessage() + "); the disc will look again when it plays.");
            }
        });
    }

    private boolean interceptSpotify(String resolved, AudioLoadResultHandler handler) {
        String trackId = dev.valkdz.cdisc.audio.source.spotify.SpotifyBridge.trackIdOf(resolved);
        if (trackId == null || spotifyBridge == null || !spotifyBridge.isUsable()) return false;

        resolveExecutor.submit(() -> resolveSpotify(trackId, resolved, handler));
        return true;
    }

    public void load(String resolved, String musicFetch, String discTitle, String discAuthor,
                     AudioLoadResultHandler handler) {
        if (loadBroadcast(resolved, discTitle, discAuthor, handler)) return;
        String youtube = "backend".equals(musicFetch) ? youtubeUrlOf(resolved) : resolved;
        if (youtube.equals(resolved)) {
            loadSmart(resolved, handler);
        } else {
            loadSmart(youtube, handler, discTitle, discAuthor);
        }
    }

    private static boolean loadBroadcast(String resolved, String title, String author,
                                         AudioLoadResultHandler handler) {
        if (!dev.valkdz.cdisc.feature.broadcast.BroadcastTrack.isAddress(resolved)) return false;
        handler.trackLoaded(new dev.valkdz.cdisc.feature.broadcast.BroadcastTrack(resolved.trim(), title, author));
        return true;
    }

    private void loadSmart(String resolved, AudioLoadResultHandler handler) {
        loadSmart(resolved, handler, null, null);
    }

    private void loadSmart(String resolved, AudioLoadResultHandler handler,
                           String preferredTitle, String preferredAuthor) {

        if (interceptSpotify(resolved, handler)) return;

        if (!isYoutubeIdentifier(resolved)) {
            loadFromSources(resolved, handler);
            return;
        }

        if (sabrResolver == null) {
            loadViaBackend(resolved, handler);
            return;
        }

        String videoId = dev.valkdz.cdisc.audio.source.youtube.SabrResolver.videoIdOf(resolved);
        Route known = videoId == null ? null : route(videoId);

        if (videoId != null && preferBackend.contains(videoId)) {
            loadViaBackend(resolved, handler);
            return;
        }

        if (known != null && Boolean.FALSE.equals(known.allowedHere())) {
            Bukkit.getLogger().info("[CDisc] This one is not offered in "
                    + sabrResolver.region() + "; going straight to the backend.");
            loadViaBackend(resolved, handler);
            return;
        }

        if (customApiResolver != null && System.currentTimeMillis() < directPausedUntil) {
            loadViaBackend(resolved, handler);
            return;
        }

        CompletableFuture
                .supplyAsync(() -> attemptDirect(resolved, preferredTitle, preferredAuthor,
                        preferredTitle != null), resolveExecutor)
                .exceptionally(ex -> Direct.NOTHING)
                .whenComplete((direct, ex) -> {
                    Direct answer = direct == null ? Direct.NOTHING : direct;

                    if (answer.track() != null) directMisses.set(0);
                    if (answer.addressRefused()) noteDirectMiss();

                    if (answer.track() != null) {
                        setTrackSourceMetadata(answer.track(), directLabel(answer.track()));
                        announce(answer.track());
                        invokeHandler(answer.track(), handler);
                        return;
                    }

                    if (answer.ageRestricted()) {
                        loadAgeRestricted(resolved, handler);
                        return;
                    }

                    Bukkit.getLogger().info("[CDisc] YouTube would not hand this track over "
                            + "directly; asking the backend.");
                    loadViaBackend(resolved, handler);
                });
    }

    // A server whose address YouTube distrusts fails every direct read, each costing a second or
    // more before the backend is asked, so after a few in a row the backend goes first for a while.
    private void noteDirectMiss() {
        if (customApiResolver == null) return;
        if (directMisses.incrementAndGet() < DIRECT_MISSES_TO_PAUSE) return;

        directMisses.set(0);
        directPausedUntil = System.currentTimeMillis() + DIRECT_PAUSE_MS;
        Bukkit.getLogger().info("[CDisc] YouTube refused this server's direct reads "
                + DIRECT_MISSES_TO_PAUSE + " times in a row; playing YouTube through the backend for the next "
                + DIRECT_PAUSE_MS / 60_000 + " minutes.");
    }

    private void resolveSpotify(String trackId, String original, AudioLoadResultHandler handler) {
        try {
            dev.valkdz.cdisc.audio.source.spotify.SpotifyBridge.Match match =
                    spotifyBridge.resolve(trackId);

            Bukkit.getLogger().info("[CDisc] Spotify: \"" + match.title() + "\" by "
                    + match.artist() + " matched by " + match.matchedBy()
                    + " to " + match.youtubeUrl());

            loadSmart(match.youtubeUrl(), handler, match.title(), match.artist());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Bukkit.getLogger().warning("[CDisc] Could not match this Spotify track to a "
                    + "YouTube video (" + e.getMessage() + "); falling back to the "
                    + "registered sources.");
            loadFromSources(original, handler);
        }
    }

    private void loadViaBackend(String resolved, AudioLoadResultHandler handler) {
        loadViaBackend(resolved, handler, false);
    }

    private void loadViaBackend(String resolved, AudioLoadResultHandler handler,
                                  boolean backendAlreadyTried) {
        resolveExecutor.submit(() -> {
            AudioTrack backend = null;
            if (customApiResolver != null && !backendAlreadyTried) {
                try {
                    backend = customApiResolver.resolve(resolved);
                } catch (Exception e) {
                    backend = null;
                }
            }
            if (backend != null) {
                setTrackSourceMetadata(backend, "Custom API (Backend)");
                announce(backend);
                invokeHandler(backend, handler);
                return;
            }

            AudioTrack replacement = substitute(
                    dev.valkdz.cdisc.audio.source.youtube.SabrResolver.videoIdOf(resolved));
            if (replacement != null) {
                setTrackSourceMetadata(replacement, directLabel(replacement));
                invokeHandler(replacement, handler);
                return;
            }
            Bukkit.getLogger().severe("[CDisc] No source could load this track.");
            handler.noMatches();
        });
    }

    private static String directLabel(AudioTrack track) {
        if (track instanceof dev.valkdz.cdisc.audio.source.youtube.SabrAudioTrack) return "YouTube SABR";
        return dev.valkdz.cdisc.audio.source.youtube.SabrResolver.EMBEDDED_SOURCE.equals(
                track.getSourceManager().getSourceName())
                ? "YouTube direct (embedded player)" : "YouTube direct (VISIONOS)";
    }

    private void announce(AudioTrack track) {
        if (!plugin.cdiscConfig().isDebug()) return;
        Bukkit.getLogger().info("[CDisc] \"" + track.getInfo().title + "\" via " + track.getUserData() + ".");
    }

    private void invokeHandler(AudioItem item, AudioLoadResultHandler handler) {
        if (item instanceof AudioTrack track) {
            handler.trackLoaded(track);
        } else if (item instanceof AudioPlaylist playlist) {
            handler.playlistLoaded(playlist);
        } else {
            handler.noMatches();
        }
    }

    private void setTrackSourceMetadata(AudioItem item, String sourceName) {
        if (item instanceof AudioTrack track) {
            track.setUserData(sourceName);
        } else if (item instanceof AudioPlaylist playlist) {
            for (AudioTrack track : playlist.getTracks()) {
                track.setUserData(sourceName);
            }
        }
    }

    public boolean hasNextSource(AudioTrack failed, String resolved) {
        return customApiResolver != null
                && !"Custom API (Backend)".equals(failed.getUserData())
                && isYoutubeIdentifier(youtubeUrlOf(resolved));
    }

    public void nextSourceAsync(AudioTrack failed, String resolved, Consumer<AudioTrack> callback) {
        String url = youtubeUrlOf(resolved);

        resolveExecutor.submit(() -> {
            AudioTrack track = null;
            try {
                CustomYoutubeApiResolver api = customApiResolver;
                if (api != null) track = api.resolve(url);
            } catch (Exception e) {
                Bukkit.getLogger().warning("[CDisc] The backend had no replacement: " + e.getMessage());
            }
            if (track != null) {
                Bukkit.getLogger().info("[CDisc] Found a replacement: going through the backend.");
                setTrackSourceMetadata(track, "Custom API (Backend)");
            }
            callback.accept(track);
        });
    }

    private String youtubeUrlOf(String resolved) {
        String videoId = backendForAgeGate().extractIdFromOwnStreamUrl(resolved);
        return videoId == null ? resolved : "https://www.youtube.com/watch?v=" + videoId;
    }

    public String backendDownloadUrlFor(String identifier) {
        String videoId = dev.valkdz.cdisc.audio.source.youtube.SabrResolver.videoIdOf(identifier);
        return videoId == null ? null : backendForAgeGate().proxyStreamUrlFor(videoId);
    }

    // SoundCloud serves a subscription-only track as a 30-second clip under the full track's
    // length, so it stops at 0:30 and a seek past that fails to decode.
    public static boolean isPreviewOnly(AudioTrack track) {
        if (track == null || track.getSourceManager() == null) return false;
        String identifier = track.getInfo().identifier;
        return "soundcloud".equals(track.getSourceManager().getSourceName())
                && identifier != null && identifier.contains("/preview/");
    }

    public static boolean isSearch(String resolved) {
        int colon = resolved.indexOf(':');
        return colon > 0 && resolved.substring(0, colon).endsWith("search");
    }

    public static boolean spotifySearchable(Config config) {
        return !config.isYoutubeEnabled()
                || (config.isSpotifyEnabled()
                        && !config.getSpotifyClientId().isEmpty()
                        && !config.getSpotifyClientSecret().isEmpty());
    }

    public static String sourceIdOf(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.startsWith("sc:")) return "soundcloud";
        if (q.startsWith("sp:")) return "spotify";
        if (q.startsWith("ym:")) return "yandex-music";
        if (q.startsWith("vk:")) return "vk-music";
        if (q.startsWith("tt:")) return "tiktok";
        if (LocalMusicLibrary.isLocalQuery(q)) return "local";
        return "youtube";
    }

    public boolean isYoutubeIdentifier(String identifier) {
        return identifier.startsWith("ytsearch:")
                || identifier.contains("youtube.com")
                || identifier.contains("youtu.be");
    }

    public boolean hasAlternative() {
        return hasCustomApi() || sabrResolver != null;
    }

    private dev.valkdz.cdisc.audio.source.youtube.InnerTubePlayer.ClientIdentity sabrIdentity() {
        Config config = plugin.cdiscConfig();

        return dev.valkdz.cdisc.audio.source.youtube.InnerTubePlayer.ClientIdentity.web(
                config.getYoutubeWebClientVersion(),
                WEB_USER_AGENT,
                config.getPOtoken(),
                config.getVisitorData(),
                null,
                config.getYoutubeSignatureTimestamp());
    }

    public AudioTrack resolveViaSabr(String identifier, String discTitle, String discAuthor) {
        return resolveViaSabr(identifier, discTitle, discAuthor, false);
    }

    public AudioTrack resolveViaSabr(String identifier, String discTitle, String discAuthor,
                                     boolean namesWin) {
        return attemptDirect(identifier, discTitle, discAuthor, namesWin).track();
    }

    public record Direct(AudioTrack track, boolean ageRestricted, boolean addressRefused) {
        static final Direct NOTHING = new Direct(null, false);

        Direct(AudioTrack track, boolean ageRestricted) {
            this(track, ageRestricted, false);
        }
    }

    private Direct attemptDirect(String identifier, String discTitle, String discAuthor,
                                 boolean namesWin) {
        return attemptDirect(identifier, discTitle, discAuthor, namesWin, true);
    }

    private Direct attemptDirect(String identifier, String discTitle, String discAuthor,
                                 boolean namesWin, boolean mayReupload) {
        if (sabrResolver == null) return Direct.NOTHING;

        String videoId = dev.valkdz.cdisc.audio.source.youtube.SabrResolver.videoIdOf(identifier);
        Route known = videoId == null ? null : route(videoId);

        if (mayReupload && known != null && REUPLOAD_PLAN.equals(known.plan())) {
            AudioTrack again = reupload(videoId, discTitle, discAuthor);
            if (again != null) return new Direct(again, false);
        }

        try {
            AudioTrack track = sabrResolver.resolve(identifier, discTitle, discAuthor, namesWin,
                    known == null ? null : known.plan());
            return new Direct(track, false, track == null);
        } catch (Exception e) {
            if (looksAgeRestricted(e.getMessage())) {
                if (!mayReupload) return new Direct(null, true);

                var refused = e instanceof dev.valkdz.cdisc.audio.source.youtube.PlaybackRefused r ? r : null;
                AudioTrack other = reupload(videoId,
                        isBlank(discTitle) && refused != null ? refused.title() : discTitle,
                        isBlank(discAuthor) && refused != null ? refused.author() : discAuthor);
                if (other != null) return new Direct(other, false);

                Bukkit.getLogger().info("[CDisc] YouTube wants an account for this one "
                        + "(age-restricted); going through the backend.");
                return new Direct(null, true);
            }
            if (e instanceof dev.valkdz.cdisc.audio.source.youtube.PlaybackRefused refused
                    && refused.regional()) {
                blockedByRegion.put(refused.videoId(),
                        new Blocked(refused.title(), refused.author()));
            }
            if (looksRefused(e.getMessage())) {
                markBlocked(dev.valkdz.cdisc.audio.source.youtube.SabrResolver.videoIdOf(identifier));
            }
            Bukkit.getLogger().warning("[CDisc] The direct read could not serve the track: " + e.getMessage());
            boolean addressRefused = !(e instanceof dev.valkdz.cdisc.audio.source.youtube.PlaybackRefused refused)
                    || refused.botCheck();
            return new Direct(null, false, addressRefused);
        }
    }

    private static boolean looksRefused(String message) {
        if (message == null || !message.contains("will not play")) return false;

        String said = message.toUpperCase(java.util.Locale.ROOT);
        return said.contains("UNPLAYABLE") || said.contains("LOGIN_REQUIRED")
                || said.contains("ERROR") || said.contains("CONTENT_CHECK_REQUIRED");
    }

    private static boolean looksAgeRestricted(String message) {
        if (message == null) return false;
        String said = message.toUpperCase(java.util.Locale.ROOT);
        return said.contains("AGE_VERIFICATION_REQUIRED")
                || said.contains("AGE_CHECK_REQUIRED")
                || said.contains("CONFIRM YOUR AGE")
                || said.contains("AGE-RESTRICTED")
                || (said.contains("LOGIN_REQUIRED") && said.contains("AGE"));
    }

    private CustomYoutubeApiResolver backendForAgeGate() {
        if (customApiResolver != null) return customApiResolver;

        CustomYoutubeApiResolver made = ageGateApi;
        if (made != null) return made;

        synchronized (this) {
            if (ageGateApi == null) {
                ageGateApi = new CustomYoutubeApiResolver(
                        BACKEND_BASE, plugin.cdiscConfig().isYoutubeProxyEnabled());
            }
            return ageGateApi;
        }
    }

    private void loadAgeRestricted(String resolved, AudioLoadResultHandler handler) {
        CustomYoutubeApiResolver backend = backendForAgeGate();

        resolveExecutor.submit(() -> {
            AudioTrack track = null;
            try {
                track = backend.resolve(resolved);
            } catch (Exception e) {
                Bukkit.getLogger().warning("[CDisc] The backend could not serve the "
                        + "age-restricted track: " + e.getMessage());
            }

            if (track != null) {
                setTrackSourceMetadata(track, "Custom API (Backend)");
                invokeHandler(track, handler);
                return;
            }

            Bukkit.getLogger().warning("[CDisc] The backend had nothing for this "
                    + "age-restricted track either.");
            loadViaBackend(resolved, handler, true);
        });
    }

    public String resolveQuery(String query) {
        if (query == null || query.isBlank()) return null;
        String q = query.trim();
        if (dev.valkdz.cdisc.feature.broadcast.BroadcastTrack.isAddress(q)) return q;

        if (LocalMusicLibrary.isLocalQuery(q)) {
            return plugin.getLocalMusic().resolveToPath(q);
        }

        if (q.startsWith("yt:")) return "ytsearch:" + q.substring(3);
        if (q.startsWith("sc:")) return "scsearch:" + q.substring(3);
        if (q.startsWith("sp:")) {
            return spotifySearchable(plugin.cdiscConfig())
                    ? "spsearch:" + q.substring(3) : "ytsearch:" + q.substring(3);
        }
        if (q.startsWith("ym:")) return "ymsearch:" + q.substring(3);
        if (q.startsWith("vk:")) return "vksearch:" + q.substring(3);
        if (q.startsWith("tt:")) {
            String rest = q.substring(3).trim();
            return rest.matches("\\d{15,21}") || rest.startsWith("http") ? rest : "ttsearch:" + rest;
        }

        if (q.startsWith("http")) {
            String twitch = twitchChannelUrl(q);
            if (twitch != null) return twitch;
            return normalizeYoutubeUrl(q);
        }

        return "ytsearch:" + q;
    }

    private String twitchChannelUrl(String url) {
        if (!plugin.cdiscConfig().isTwitchEnabled()) return null;
        Matcher m = TWITCH_URL.matcher(url.trim());
        if (!m.matches()) return null;

        // The Twitch source matches this one spelling only: no http, no trailing slash, no query.
        return "https://www.twitch.tv/" + m.group(1);
    }

    public String twitchChannelOf(String query) {
        if (query == null) return null;
        Matcher m = TWITCH_URL.matcher(query.trim());
        return m.matches() ? m.group(1) : null;
    }

    public String discordTitleOf(String query) {
        if (!plugin.cdiscConfig().isDiscordEnabled()) return null;

        if (Bukkit.isPrimaryThread()) {
            return dev.valkdz.cdisc.audio.source.DiscordSource.titleOf(query);
        }
        return dev.valkdz.cdisc.audio.source.DiscordSource.bestTitleOf(query);
    }

    private String normalizeYoutubeUrl(String url) {
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) {
            return url;
        }

        int shortIdx = url.indexOf("youtu.be/");
        if (shortIdx >= 0) {
            String id = url.substring(shortIdx + "youtu.be/".length());
            int cut = indexOfAny(id, '?', '&', '/');
            if (cut >= 0) id = id.substring(0, cut);
            return id.isEmpty() ? url : "https://www.youtube.com/watch?v=" + id;
        }

        String videoId = queryParam(url, "v");
        if (videoId != null && !videoId.isEmpty()) {
            return "https://www.youtube.com/watch?v=" + videoId;
        }

        return url;
    }

    private String queryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private int indexOfAny(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    public void shutdown() {
        resolveExecutor.shutdownNow();
        sources.forEach(AudioSourceManager::shutdown);
        if (customApiResolver != null) customApiResolver.shutdown();

        CustomYoutubeApiResolver aged = ageGateApi;
        if (aged != null && aged != customApiResolver) aged.shutdown();
    }
}
