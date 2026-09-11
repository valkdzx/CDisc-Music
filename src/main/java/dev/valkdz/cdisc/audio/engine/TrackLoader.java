package dev.valkdz.cdisc.audio.engine;

import com.dunctebot.sourcemanagers.mixcloud.MixcloudAudioSourceManager;
import com.dunctebot.sourcemanagers.ocremix.OCRemixAudioSourceManager;
import com.dunctebot.sourcemanagers.pornhub.PornHubAudioSourceManager;
import com.dunctebot.sourcemanagers.reddit.RedditAudioSourceManager;
import com.dunctebot.sourcemanagers.soundgasm.SoundGasmAudioSourceManager;
import com.dunctebot.sourcemanagers.tiktok.TikTokAudioSourceManager;
import com.github.topi314.lavasearch.SearchManager;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.github.topi314.lavasrc.vkmusic.VkMusicSourceManager;
import com.github.topi314.lavasrc.yandexmusic.YandexMusicSourceManager;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.*;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSource;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.*;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.CustomYoutubeApiResolver;
import dev.valkdz.cdisc.audio.LocalMusicLibrary;
import dev.valkdz.cdisc.util.Config;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
    private final AudioPlayerManager lavaPlayer;
    private final SearchManager searchManager = new SearchManager();

    private static final String SABR_MARKER = "No supported audio streams available";

    private static final long PREFER_BACKEND_MS = 10 * 60 * 1000L;

    private static final String WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private YoutubeAudioSourceManager youtubeManager;
    private CustomYoutubeApiResolver customApiResolver;
    private dev.valkdz.cdisc.audio.sabr.SabrResolver sabrResolver;
    private dev.valkdz.cdisc.audio.spotify.SpotifyBridge spotifyBridge;

    private volatile long preferBackendUntil;

    private volatile CustomYoutubeApiResolver ageGateApi;

    private final ExecutorService resolveExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cdisc-yt-resolve");
        t.setDaemon(true);
        return t;
    });

    public TrackLoader(Main plugin) {
        this.plugin = plugin;
        this.lavaPlayer = new DefaultAudioPlayerManager();

        this.lavaPlayer.setFrameBufferDuration(5000);
        registerSources();
    }

    private void registerSources() {
        Config config = plugin.cdiscConfig();

        if (config.isYoutubeEnabled()) {
            YoutubeSourceOptions options = new YoutubeSourceOptions();
            String rcUrl = config.getRemoteCipherServerUrl();
            String rcPass = config.getRemoteCipherServerPassword();
            if (!rcUrl.isEmpty()) {
                options.setRemoteCipher(rcUrl, rcPass, "CDisc Music Plugin");
            }

            Web web = new Web();
            installPoToken(config);

            youtubeManager = new YoutubeAudioSourceManager(options, buildClients(config, web));

            String refreshToken = config.getYoutubeOauthRefreshToken();
            if (config.isYoutubeOauthEnabled() && !refreshToken.isEmpty()) {
                youtubeManager.useOauth2(refreshToken, true);
            }
            lavaPlayer.registerSourceManager(youtubeManager);

            boolean customApi = config.getYoutubeCustomApi();
            boolean useProxy = config.isYoutubeProxyEnabled();
            customApiResolver = customApi
                    ? new CustomYoutubeApiResolver("https://2281273.xyz/", new HttpAudioSourceManager(), useProxy)
                    : null;

            sabrResolver = config.isYoutubeSabrEnabled()
                    ? new dev.valkdz.cdisc.audio.sabr.SabrResolver(this::sabrIdentity, rcUrl)
                    : null;

            java.net.http.HttpClient bridgeHttp = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();

            spotifyBridge = new dev.valkdz.cdisc.audio.spotify.SpotifyBridge(
                    bridgeHttp,
                    new dev.valkdz.cdisc.audio.sabr.YouTubeSearch(
                            bridgeHttp, config.getYoutubeWebClientVersion()),
                    config::getSpotifyClientId,
                    config::getSpotifyClientSecret,
                    config::getPoTokenBackendUrl,
                    config::getPoTokenBackendPassword,
                    () -> sabrResolver == null ? null : sabrResolver.visitorDataOrNull());
        }

        if (config.isSoundcloudEnabled()) {
            lavaPlayer.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        }

        if (config.isSpotifyEnabled()) {
            String clientId = config.getSpotifyClientId();
            String clientSecret = config.getSpotifyClientSecret();
            if (!clientId.isEmpty() && !clientSecret.isEmpty()) {
                SpotifySourceManager spotify = new SpotifySourceManager(
                        clientId,
                        clientSecret,
                        "US",
                        lavaPlayer,
                        new DefaultMirroringAudioTrackResolver(null)
                );
                lavaPlayer.registerSourceManager(spotify);
                searchManager.registerSearchManager(spotify);
            }
        }

        if (config.isYandexMusicEnabled()) {
            String token = config.getYandexMusicAccessToken();
            if (!token.isEmpty()) {
                YandexMusicSourceManager ym = new YandexMusicSourceManager(token);
                lavaPlayer.registerSourceManager(ym);
                searchManager.registerSearchManager(ym);
            }
        }

        if (config.isTiktokEnabled()) {
            TikTokAudioSourceManager tt = new TikTokAudioSourceManager();
            lavaPlayer.registerSourceManager(tt);
        }

        if (config.isPornhubEnabled()) {
            PornHubAudioSourceManager ph = new PornHubAudioSourceManager();
            lavaPlayer.registerSourceManager(ph);
        }

        if (config.isRedditEnabled()) {
            RedditAudioSourceManager rd = new RedditAudioSourceManager();
            lavaPlayer.registerSourceManager(rd);
        }

        if (config.isMixcloudEnabled()) {
            MixcloudAudioSourceManager mc = new MixcloudAudioSourceManager();
            lavaPlayer.registerSourceManager(mc);
        }

        if (config.isOcremixEnabled()) {
            OCRemixAudioSourceManager rm = new OCRemixAudioSourceManager();
            lavaPlayer.registerSourceManager(rm);
        }

        if (config.isSoundgasmEnabled()) {
            SoundGasmAudioSourceManager sg = new SoundGasmAudioSourceManager();
            lavaPlayer.registerSourceManager(sg);
        }

        if (config.isVkMusicEnabled()) {
            String token = config.getVkMusicUserToken();
            if (!token.isEmpty()) {
                VkMusicSourceManager vk = new VkMusicSourceManager(token);
                lavaPlayer.registerSourceManager(vk);
                searchManager.registerSearchManager(vk);
            }
        }

        if (config.isTwitchEnabled()) {
            lavaPlayer.registerSourceManager(new dev.valkdz.cdisc.audio.twitch.TwitchSourceManager());
        }

        if (config.isBandcampEnabled()) {
            BandcampAudioSourceManager bc = new BandcampAudioSourceManager();
            lavaPlayer.registerSourceManager(bc);
        }

        if (config.isVimeoEnabled()) {
            VimeoAudioSourceManager vm = new VimeoAudioSourceManager();
            lavaPlayer.registerSourceManager(vm);
        }

        if (config.isHttpEnabled()) {
            AudioSourceManagers.registerRemoteSources(lavaPlayer);
        } else {

            List<String> allowed = new ArrayList<>();
            if (config.isDiscordEnabled()) {
                allowed.addAll(List.of(dev.valkdz.cdisc.audio.DiscordSource.URL_PREFIXES));
            }
            if (!allowed.isEmpty()) {
                lavaPlayer.registerSourceManager(
                        new ScopedHttpAudioSourceManager(allowed.toArray(new String[0])));
            }
        }

        if (config.isLocalEnabled()) {
            AudioSourceManagers.registerLocalSource(lavaPlayer);
        }
    }

    private Client[] buildClients(Config config, Web web) {
        List<Client> clients = new ArrayList<>();

        for (String name : config.getYoutubeClients()) {
            Client client = switch (name) {
                case "android" -> new Android();
                case "android-music" -> new AndroidMusic();
                case "android-vr" -> new AndroidVr();
                case "ios" -> new Ios();
                case "music" -> new Music();
                case "mweb" -> new MWeb();
                case "tv" -> new Tv();

                case "web" -> web;
                case "web-embedded" -> new WebEmbedded();
                default -> null;
            };

            if (client == null) {
                plugin.getLogger().warning("Unknown YouTube client in sources.yml: '"
                        + name + "' — skipped.");
                continue;
            }
            clients.add(client);
        }

        if (clients.isEmpty()) {
            plugin.getLogger().warning(
                    "No usable YouTube clients configured; falling back to the defaults.");
            return new Client[]{new AndroidVr(), new Ios(), new Music(), web,
                    new Tv(), new WebEmbedded(), new MWeb()};
        }
        return clients.toArray(new Client[0]);
    }

    private void installPoToken(Config config) {
        String poToken = config.getPOtoken();
        String visitorData = config.getVisitorData();

        if (poToken.isEmpty() && visitorData.isEmpty()) {

            YoutubeSource.setPoTokenAndVisitorData(null, null);
            return;
        }

        if (poToken.isEmpty()) {

            plugin.getLogger().info(
                    "visitor-data set with no po-token: the visitor identity is what "
                    + "YouTube attests, and it carries playback on its own.");
            YoutubeSource.setPoTokenAndVisitorData(null, visitorData);
            return;
        }

        if (visitorData.isEmpty()) {
            plugin.getLogger().info(
                    "po-token set with no visitor-data: it will ride on the playback "
                    + "URL only, and the player request keeps youtube-source's own "
                    + "visitor id.");
            YoutubeSource.setPoTokenAndVisitorData(poToken, null);
            return;
        }

        YoutubeSource.setPoTokenAndVisitorData(poToken, visitorData);
    }

    public void reloadSources() {
        registerSources();
    }

    public void setPcmOutput(boolean pcm) {
        lavaPlayer.getConfiguration().setOutputFormat(pcm
                ? StandardAudioDataFormats.DISCORD_PCM_S16_LE
                : StandardAudioDataFormats.DISCORD_OPUS);
    }

    public AudioPlayer createPlayer() {
        return lavaPlayer.createPlayer();
    }

    public YoutubeAudioSourceManager getYoutubeSourceManager() {
        return youtubeManager;
    }

    public boolean hasCustomApi() {
        return customApiResolver != null;
    }

    public void loadItem(String resolved, AudioLoadResultHandler handler) {
        if (interceptSpotify(resolved, handler)) return;
        lavaPlayer.loadItem(resolved, handler);
    }

    private boolean interceptSpotify(String resolved, AudioLoadResultHandler handler) {
        String trackId = dev.valkdz.cdisc.audio.spotify.SpotifyBridge.trackIdOf(resolved);
        if (trackId == null || spotifyBridge == null || !spotifyBridge.isUsable()) return false;

        resolveExecutor.submit(() -> resolveSpotify(trackId, resolved, handler));
        return true;
    }

    public void load(String resolved, String musicFetch, String discTitle, String discAuthor,
                     AudioLoadResultHandler handler) {
        if ("backend".equals(musicFetch) && customApiResolver != null) {
            String videoId = customApiResolver.extractIdFromOwnStreamUrl(resolved);
            resolveExecutor.submit(() -> {
                AudioTrack track = customApiResolver.wrapStream(
                        lavaPlayer, resolved, videoId, discTitle, discAuthor, 0, null, null);
                if (track != null) {
                    setTrackSourceMetadata(track, "Custom API (Backend)");
                    handler.trackLoaded(track);
                } else {
                    handler.loadFailed(new FriendlyException(
                            "Backend stream unreachable", FriendlyException.Severity.SUSPICIOUS, null));
                }
            });
        } else if ("lavaplayer".equals(musicFetch)) {
            lavaPlayer.loadItem(resolved, handler);
        } else {
            loadSmart(resolved, handler);
        }
    }

    private void loadSmart(String resolved, AudioLoadResultHandler handler) {
        loadSmart(resolved, handler, null, null);
    }

    private void loadSmart(String resolved, AudioLoadResultHandler handler,
                           String preferredTitle, String preferredAuthor) {

        if (interceptSpotify(resolved, handler)) return;

        if (!isYoutubeIdentifier(resolved)) {
            lavaPlayer.loadItem(resolved, handler);
            return;
        }

        if (sabrResolver == null) {
            loadViaLibraries(resolved, handler);
            return;
        }

        CompletableFuture
                .supplyAsync(() -> attemptDirect(resolved, preferredTitle, preferredAuthor,
                        preferredTitle != null), resolveExecutor)
                .exceptionally(ex -> Direct.NOTHING)
                .whenComplete((direct, ex) -> {
                    Direct answer = direct == null ? Direct.NOTHING : direct;

                    if (answer.track() != null) {
                        setTrackSourceMetadata(answer.track(), "YouTube (direct)");
                        invokeHandler(answer.track(), handler);
                        return;
                    }

                    if (answer.ageRestricted()) {
                        loadAgeRestricted(resolved, handler);
                        return;
                    }

                    Bukkit.getLogger().info("[CDisc] YouTube would not hand this track over "
                            + "directly; trying the libraries.");
                    loadViaLibraries(resolved, handler);
                });
    }

    private void resolveSpotify(String trackId, String original, AudioLoadResultHandler handler) {
        try {
            dev.valkdz.cdisc.audio.spotify.SpotifyBridge.Match match =
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
            lavaPlayer.loadItem(original, handler);
        }
    }

    private void loadViaLibraries(String resolved, AudioLoadResultHandler handler) {
        loadViaLibraries(resolved, handler, false);
    }

    private void loadViaLibraries(String resolved, AudioLoadResultHandler handler,
                                  boolean backendAlreadyTried) {

        java.util.concurrent.atomic.AtomicReference<String> refusal =
                new java.util.concurrent.atomic.AtomicReference<>();

        CompletableFuture<AudioItem> apiFuture = customApiResolver == null
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(
                        () -> (AudioItem) customApiResolver.resolve(lavaPlayer, resolved), resolveExecutor
                ).exceptionally(ex -> null);

        CompletableFuture<AudioItem> ytFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return youtubeManager.loadItem(lavaPlayer, new AudioReference(resolved, null));
            } catch (Exception e) {
                refusal.set(e.getMessage());
                return null;
            }
        }, resolveExecutor).exceptionally(ex -> null);

        if (prefersBackend()) {
            preferBackendOver(apiFuture, ytFuture, handler, resolved, refusal,
                              backendAlreadyTried);
            return;
        }

        ytFuture.whenComplete((ytItem, ytEx) -> {
            if (ytItem != null) {

                setTrackSourceMetadata(ytItem, "youtube-source");
                invokeHandler(ytItem, handler);
            } else {
                Bukkit.getLogger().warning("[CDisc] youtube-source returned nothing; "
                        + "switching to the custom API...");
                apiFuture.whenComplete((apiItem, apiEx) -> {
                    if (apiItem != null) {
                        Bukkit.getLogger().info("[CDisc] Loaded through the custom YouTube API.");
                        setTrackSourceMetadata(apiItem, "Custom API (Backend)");
                        invokeHandler(apiItem, handler);
                    } else if (!backendAlreadyTried && looksAgeRestricted(refusal.get())) {

                        Bukkit.getLogger().info("[CDisc] youtube-source was refused on age; "
                                + "asking the backend.");
                        loadAgeRestricted(resolved, handler);
                    } else {
                        Bukkit.getLogger().severe("[CDisc] No source could load this track.");
                        handler.noMatches();
                    }
                });
            }
        });
    }

    private void preferBackendOver(CompletableFuture<AudioItem> apiFuture,
                                   CompletableFuture<AudioItem> ytFuture,
                                   AudioLoadResultHandler handler, String resolved,
                                   java.util.concurrent.atomic.AtomicReference<String> refusal,
                                   boolean backendAlreadyTried) {
        apiFuture.whenComplete((apiItem, apiEx) -> {
            if (apiItem != null) {
                setTrackSourceMetadata(apiItem, "Custom API (Backend)");
                invokeHandler(apiItem, handler);
                return;
            }
            ytFuture.whenComplete((ytItem, ytEx) -> {
                if (ytItem != null) {
                    setTrackSourceMetadata(ytItem, "youtube-source");
                    invokeHandler(ytItem, handler);
                } else if (!backendAlreadyTried && looksAgeRestricted(refusal.get())) {
                    Bukkit.getLogger().info("[CDisc] youtube-source was refused on age; "
                            + "asking the backend.");
                    loadAgeRestricted(resolved, handler);
                } else {
                    Bukkit.getLogger().severe("[CDisc] No source could load this track.");
                    handler.noMatches();
                }
            });
        });
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

    public CompletableFuture<Boolean> probePlayability(AudioTrack track) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        AudioPlayer probePlayer = lavaPlayer.createPlayer();

        probePlayer.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackException(AudioPlayer p, AudioTrack t, FriendlyException exception) {
                result.complete(false);
            }
        });

        probePlayer.playTrack(track.makeClone());

        int timeoutSeconds = plugin.cdiscConfig().getYoutubeProbeTimeoutSeconds();
        resolveExecutor.submit(() -> {
            try {
                boolean gotFrame = probePlayer.provide(timeoutSeconds, TimeUnit.SECONDS) != null;
                result.complete(gotFrame);
            } catch (Exception e) {
                result.complete(false);
            } finally {
                probePlayer.destroy();
            }
        });

        return result;
    }

    private static String pathOf(AudioTrack track) {
        return track instanceof dev.valkdz.cdisc.audio.sabr.DirectAudioTrack
                ? "direct link" : "SABR";
    }

    public AudioTrack resolveViaBackend(String resolved) {
        return customApiResolver == null ? null : customApiResolver.resolve(lavaPlayer, resolved);
    }

    public void resolveViaBackendAsync(String resolved, Consumer<AudioTrack> callback) {
        if (!hasAlternative()) {
            callback.accept(null);
            return;
        }
        resolveExecutor.submit(() -> {

            Direct direct = attemptDirect(resolved, null, null, false);
            AudioTrack track = direct.track();
            if (track != null) {
                Bukkit.getLogger().info("[CDisc] Found a replacement: reading straight from "
                        + "YouTube (" + pathOf(track) + ").");
                setTrackSourceMetadata(track, "SABR (direct)");
                callback.accept(track);
                return;
            }

            try {

                track = direct.ageRestricted()
                        ? backendForAgeGate().resolve(lavaPlayer, resolved)
                        : resolveViaBackend(resolved);
            } catch (Exception e) {
                Bukkit.getLogger().warning("[CDisc] The custom API had no replacement: "
                        + e.getMessage());
                track = null;
            }
            if (track != null) {
            Bukkit.getLogger().info("[CDisc] Found a replacement: going through the custom API.");
                setTrackSourceMetadata(track, "Custom API (Backend)");
            }
            callback.accept(track);
        });
    }

    public String backendStreamUrlFor(String videoId) {
        return customApiResolver == null ? null : customApiResolver.streamUrlFor(videoId);
    }

    public String backendDownloadUrlFor(String identifier) {
        String videoId = dev.valkdz.cdisc.audio.sabr.SabrResolver.videoIdOf(identifier);
        return videoId == null ? null : backendForAgeGate().proxyStreamUrlFor(videoId);
    }

    public static boolean isSearch(String resolved) {
        int colon = resolved.indexOf(':');
        return colon > 0 && resolved.substring(0, colon).endsWith("search");
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

    public static boolean isSabrFailure(Throwable error) {
        return mentionsSabr(error, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static boolean mentionsSabr(Throwable error, java.util.Set<Throwable> seen) {
        if (error == null || !seen.add(error)) return false;

        String message = error.getMessage();
        if (message != null && message.contains(SABR_MARKER)) return true;

        for (Throwable suppressed : error.getSuppressed()) {
            if (mentionsSabr(suppressed, seen)) return true;
        }
        return mentionsSabr(error.getCause(), seen);
    }

    public void markYoutubeSourceUnusable() {
        preferBackendUntil = System.currentTimeMillis() + PREFER_BACKEND_MS;
    }

    public boolean prefersBackend() {
        return (hasCustomApi() || hasSabr()) && System.currentTimeMillis() < preferBackendUntil;
    }

    public boolean hasAlternative() {
        return hasCustomApi() || hasSabr();
    }

    public void trustYoutubeSourceAgain() {
        preferBackendUntil = 0L;
    }

    public boolean hasSabr() {
        return sabrResolver != null;
    }

    private dev.valkdz.cdisc.audio.sabr.InnerTubePlayer.ClientIdentity sabrIdentity() {
        Config config = plugin.cdiscConfig();

        return dev.valkdz.cdisc.audio.sabr.InnerTubePlayer.ClientIdentity.web(
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

    public record Direct(AudioTrack track, boolean ageRestricted) {
        static final Direct NOTHING = new Direct(null, false);
    }

    private Direct attemptDirect(String identifier, String discTitle, String discAuthor,
                                 boolean namesWin) {
        if (sabrResolver == null) return Direct.NOTHING;

        try {
            return new Direct(sabrResolver.resolve(identifier, discTitle, discAuthor, namesWin),
                              false);
        } catch (Exception e) {
            if (looksAgeRestricted(e.getMessage())) {
                Bukkit.getLogger().info("[CDisc] YouTube wants an account for this one "
                        + "(age-restricted); going through the backend.");
                return new Direct(null, true);
            }
            Bukkit.getLogger().warning("[CDisc] SABR could not serve the track: " + e.getMessage());
            return Direct.NOTHING;
        }
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
                        BACKEND_BASE, new HttpAudioSourceManager(),
                        plugin.cdiscConfig().isYoutubeProxyEnabled());
            }
            return ageGateApi;
        }
    }

    private void loadAgeRestricted(String resolved, AudioLoadResultHandler handler) {
        CustomYoutubeApiResolver backend = backendForAgeGate();

        resolveExecutor.submit(() -> {
            AudioTrack track = null;
            try {
                track = backend.resolve(lavaPlayer, resolved);
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
                    + "age-restricted track either; trying the libraries.");
            loadViaLibraries(resolved, handler, true);
        });
    }

    public String resolveQuery(String query) {
        if (query == null || query.isBlank()) return null;
        String q = query.trim();

        if (LocalMusicLibrary.isLocalQuery(q)) {
            return plugin.getLocalMusic().resolveToPath(q);
        }

        if (q.startsWith("yt:")) return "ytsearch:" + q.substring(3);
        if (q.startsWith("sc:")) return "scsearch:" + q.substring(3);
        if (q.startsWith("sp:")) return "spsearch:" + q.substring(3);
        if (q.startsWith("ym:")) return "ymsearch:" + q.substring(3);
        if (q.startsWith("vk:")) return "vksearch:" + q.substring(3);
        if (q.startsWith("tt:")) return "ttsearch:" + q.substring(3);

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
            return dev.valkdz.cdisc.audio.DiscordSource.titleOf(query);
        }
        return dev.valkdz.cdisc.audio.DiscordSource.bestTitleOf(query);
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
        if (customApiResolver != null) customApiResolver.shutdown();

        CustomYoutubeApiResolver aged = ageGateApi;
        if (aged != null && aged != customApiResolver) aged.shutdown();
    }
}
