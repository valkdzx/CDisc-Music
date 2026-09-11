package dev.valkdz.cdisc;

import com.sedmelluq.discord.lavaplayer.natives.ConnectorNativeLibLoader;
import de.tr7zw.changeme.nbtapi.NBT;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.command.CDiscCommand;
import dev.valkdz.cdisc.gui.PlayerGuiListener;
import dev.valkdz.cdisc.gui.PlayerActions;
import dev.valkdz.cdisc.gui.PlayerGuiManager;
import dev.valkdz.cdisc.gui.QueueGuiListener;
import dev.valkdz.cdisc.gui.QueueGuiManager;
import dev.valkdz.cdisc.listener.JukeboxListener;
import dev.valkdz.cdisc.listener.TrackProgressDisplay;
import dev.valkdz.cdisc.metrics.CDiscMetrics;
import dev.valkdz.cdisc.portable.PortableJukeboxListener;
import dev.valkdz.cdisc.portable.PortableJukeboxManager;
import dev.valkdz.cdisc.update.UpdateChecker;
import dev.valkdz.cdisc.util.Config;
import dev.valkdz.cdisc.util.MessageManager;
import dev.valkdz.cdisc.voice.VoiceBackendManager;
import dev.valkdz.cdisc.youtube.YouTubeOAuthSetup;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class Main extends JavaPlugin {

    private static Main instance;
    public static Main getInstance() { return instance; }

    private LavaPlayerManager audioPlayerManager;
    private YouTubeOAuthSetup youtubeOAuthSetup;
    private MessageManager messageManager;
    private Config config;
    private TrackProgressDisplay trackProgressDisplay;
    private JukeboxListener jukeboxListener;
    private PlayerGuiManager playerGuiManager;
    private PlayerActions playerActions;
    private QueueGuiManager queueGuiManager;
    private dev.valkdz.cdisc.gui.LyricsGuiManager lyricsGuiManager;
    private dev.valkdz.cdisc.lyrics.HologramPresets hologramPresets;
    private dev.valkdz.cdisc.gui.ScreenPreferences screenPreferences;
    private dev.valkdz.cdisc.lyrics.HologramPreview hologramPreview;
    private final dev.valkdz.cdisc.lyrics.PresetOffers presetOffers =
            new dev.valkdz.cdisc.lyrics.PresetOffers();
    private dev.valkdz.cdisc.gui.JukeboxViewers jukeboxViewers;
    private VoiceBackendManager voiceBackendManager;
    private UpdateChecker updateChecker;
    private PortableJukeboxManager portableJukeboxManager;
    private dev.valkdz.cdisc.speaker.SpeakerGroupManager speakerGroupManager;
    private dev.valkdz.cdisc.gui.PairGuiManager pairGuiManager;
    private dev.valkdz.cdisc.speaker.SpeakerParticles speakerParticles;
    private dev.valkdz.cdisc.gui.PlaylistGuiManager playlistGuiManager;
    private dev.valkdz.cdisc.audio.SearchResults searchResults;
    private dev.valkdz.cdisc.audio.LocalMusicLibrary localMusic;
    private dev.valkdz.cdisc.audio.LocalDownloader localDownloader;
    private dev.valkdz.cdisc.audio.TrackDownloader trackDownloader;
    private dev.valkdz.cdisc.audio.PlaybackResume playbackResume;
    private dev.valkdz.cdisc.permission.PermissionsConfig permissions;
    private dev.valkdz.cdisc.youtube.PoTokenService poTokenService;
    private dev.valkdz.cdisc.lyrics.LyricsService lyricsService;
    private dev.valkdz.cdisc.lyrics.LyricsDisplay lyricsDisplay;
    private dev.valkdz.cdisc.lyrics.CarriedLyrics carriedLyrics;

    private Object plasmoAddon;

    @Override
    public void onLoad() {

        saveDefaultConfig();
        config = new Config(this);

        // The config must already be saved: Plasmo Voice initialises registered addons
        // during its own onEnable, and reads ours there.
        plasmoAddon = dev.valkdz.cdisc.voice.plasmovoice.PlasmoVoiceActivator.tryLoad(this);
    }

    @Override
    public void onEnable() {
        instance = this;
        messageManager = new MessageManager(this);

        permissions = new dev.valkdz.cdisc.permission.PermissionsConfig(this);

        suppressNoisyYoutubeLogs();

        // Must exist before LavaPlayerManager starts a session: playback asks it
        // whether the jukebox has speakers paired to it.
        speakerGroupManager = new dev.valkdz.cdisc.speaker.SpeakerGroupManager(this);

        localMusic = new dev.valkdz.cdisc.audio.LocalMusicLibrary(this);
        if (localMusic.isEnabled()) {
            getLogger().info("Local music folder: " + localMusic.root());
            greetIfFolderEmpty();
        }

        localDownloader = new dev.valkdz.cdisc.audio.LocalDownloader(this);
        trackDownloader = new dev.valkdz.cdisc.audio.TrackDownloader(this);

        audioPlayerManager = new LavaPlayerManager(this);

        audioPlayerManager.startQueuePersistence();
        youtubeOAuthSetup = new YouTubeOAuthSetup(this);

        poTokenService = new dev.valkdz.cdisc.youtube.PoTokenService(this);
        poTokenService.start();

        lyricsService = new dev.valkdz.cdisc.lyrics.LyricsService(this);
        lyricsDisplay = new dev.valkdz.cdisc.lyrics.LyricsDisplay(this, lyricsService);
        lyricsDisplay.start();

        carriedLyrics = new dev.valkdz.cdisc.lyrics.CarriedLyrics(this, lyricsService);
        carriedLyrics.start();

        try {
            ConnectorNativeLibLoader.loadConnectorLibrary();
            getLogger().info("Native audio libraries (libmpg123-0, connector) preloaded.");
        } catch (Throwable t) {
            getLogger().warning("Failed to preload native audio libraries: " + t.getMessage()
                    + " — they will load lazily on first playback instead.");
        }

        boolean nbtReady = NBT.preloadApi();
        if (!nbtReady) {
            getLogger().warning("NBT-API reported it may not fully support this server version. "
                    + "Continuing anyway — jukebox disc animation syncing may be degraded, but playback is unaffected.");
        } else {
            getLogger().info("NBT-API successfully initialized!");
        }

        Objects.requireNonNull(getCommand("cdisc")).setExecutor(new CDiscCommand(this));
        Objects.requireNonNull(getCommand("cdisc")).setTabCompleter(new CDiscCommand(this));

        jukeboxListener = new JukeboxListener(this);
        getServer().getPluginManager().registerEvents(jukeboxListener, this);

        trackProgressDisplay = new TrackProgressDisplay(this);
        getServer().getPluginManager().registerEvents(trackProgressDisplay, this);
        trackProgressDisplay.start();

        jukeboxViewers = new dev.valkdz.cdisc.gui.JukeboxViewers();
        playerGuiManager = new PlayerGuiManager(this);
        playerActions = new PlayerActions(this);
        getServer().getPluginManager().registerEvents(new PlayerGuiListener(this), this);
        playerGuiManager.start();

        hologramPresets = new dev.valkdz.cdisc.lyrics.HologramPresets(this);
        screenPreferences = new dev.valkdz.cdisc.gui.ScreenPreferences(this);
        hologramPreview = new dev.valkdz.cdisc.lyrics.HologramPreview(this);

        lyricsGuiManager = new dev.valkdz.cdisc.gui.LyricsGuiManager(this);
        getServer().getPluginManager().registerEvents(
                new dev.valkdz.cdisc.gui.LyricsGuiListener(this), this);

        queueGuiManager = new QueueGuiManager(this);
        getServer().getPluginManager().registerEvents(new QueueGuiListener(this), this);

        pairGuiManager = new dev.valkdz.cdisc.gui.PairGuiManager(this);
        getServer().getPluginManager().registerEvents(new dev.valkdz.cdisc.gui.PairGuiListener(this), this);

        playlistGuiManager = new dev.valkdz.cdisc.gui.PlaylistGuiManager(this);
        getServer().getPluginManager().registerEvents(new dev.valkdz.cdisc.gui.PlaylistGuiListener(this), this);

        searchResults = new dev.valkdz.cdisc.audio.SearchResults(this);

        playbackResume = new dev.valkdz.cdisc.audio.PlaybackResume(this);
        getServer().getPluginManager().registerEvents(playbackResume, this);
        playbackResume.load();
        getServer().getPluginManager().registerEvents(
                new dev.valkdz.cdisc.speaker.SpeakerProtectionListener(this), this);

        speakerParticles = new dev.valkdz.cdisc.speaker.SpeakerParticles(this);
        speakerParticles.start();

        audioPlayerManager.setOnSessionEnded(block -> {
            playerGuiManager.forceCloseFor(block);
            lyricsDisplay.clear(block);
            dev.valkdz.cdisc.lyrics.LyricsPrefs.forget(block);
            dev.valkdz.cdisc.speaker.SpeakerSettings.forget(block);
            queueGuiManager.forceCloseFor(block);
            jukeboxViewers.clear(block);
            jukeboxListener.clearControlled(block);

            if (portableJukeboxManager != null) {
                portableJukeboxManager.onSessionEnded(block);
            }
        });

        portableJukeboxManager = new PortableJukeboxManager(this);
        getServer().getPluginManager().registerEvents(new PortableJukeboxListener(this), this);
        portableJukeboxManager.start();

        updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.check();

        voiceBackendManager = new VoiceBackendManager(this);
        voiceBackendManager.setup();

        if (voiceBackendManager.getBackend() != null) {
            float distance = (float) getConfig().getDouble("svc-config.distance", 32.0);
            audioPlayerManager.setVoiceBackend(voiceBackendManager.getBackend(), distance);
        }

        int orphans = audioPlayerManager.getAnchorManager().sweepOrphans();
        if (orphans > 0) {
            getLogger().info("Removed " + orphans + " leftover sound anchor(s) from a previous session.");
        }

        int strayLyrics = lyricsDisplay.sweepOrphans() + hologramPreview.sweepOrphans()
                + carriedLyrics.sweepOrphans();
        if (strayLyrics > 0) {
            getLogger().info("Removed " + strayLyrics + " leftover lyrics hologram(s) from a previous session.");
        }

        getServer().getScheduler().runTaskLater(this, () -> {
            if (isEnabled() && playbackResume != null) {
                playbackResume.resumeLoadedChunks();
            }
        }, 100L);

        if (isEnabled()) {
            // Guarded so a startup that already failed never leaves a submitter it cannot stop.
            CDiscMetrics.start(this);
        }
    }

    @Override
    public void onDisable() {
        if (localDownloader != null) {
            localDownloader.shutdown();
        }

        if (playbackResume != null) {
            playbackResume.save();
        }
        if (trackProgressDisplay != null) {
            try {
                trackProgressDisplay.cancel();
            } catch (IllegalStateException ignored) {
            }
            trackProgressDisplay.clearAll();
        }
        if (playerGuiManager != null) {
            playerGuiManager.stop();
        }
        if (playlistGuiManager != null) {

            playlistGuiManager.stop();
        }
        if (speakerParticles != null) {
            speakerParticles.stop();
        }
        if (portableJukeboxManager != null) {
            portableJukeboxManager.stop();
        }
        if (hologramPreview != null) {
            hologramPreview.hideAll();
        }

        if (hologramPresets != null) {

            hologramPresets.saveNow();
        }

        if (screenPreferences != null) {
            screenPreferences.saveNow();
        }

        if (carriedLyrics != null) {
            carriedLyrics.stop();
            carriedLyrics.clearAll();
        }

        if (lyricsDisplay != null) {
            lyricsDisplay.stop();
            lyricsDisplay.clearAll();
        }
        if (lyricsService != null) {
            lyricsService.shutdown();
        }
        if (poTokenService != null) {
            poTokenService.stop();
        }
        audioPlayerManager.shutdown();
        youtubeOAuthSetup.shutdown();
    }

    public void onSimpleVoiceChatReady(Object voicechatApi) {
        String categoryId = config.getSvcCategoryId();
        float distance = (float) config.getSvcDistance();

        voiceBackendManager.activateSimpleVoiceChat(voicechatApi, categoryId);
        audioPlayerManager.setVoiceBackend(voiceBackendManager.getBackend(), distance);
    }

    public LavaPlayerManager getAudioPlayerManager() { return audioPlayerManager; }
    public MessageManager getMessageManager() { return messageManager; }
    public Config cdiscConfig() { return config; }
    public dev.valkdz.cdisc.permission.PermissionsConfig getPermissions() { return permissions; }
    public YouTubeOAuthSetup getYouTubeOAuthSetup() { return youtubeOAuthSetup; }
    public TrackProgressDisplay getTrackProgressDisplay() { return trackProgressDisplay; }
    public PlayerGuiManager getPlayerGuiManager() { return playerGuiManager; }

    public PlayerActions getPlayerActions() { return playerActions; }
    public dev.valkdz.cdisc.gui.JukeboxViewers getJukeboxViewers() { return jukeboxViewers; }
    public QueueGuiManager getQueueGuiManager() { return queueGuiManager; }

    public dev.valkdz.cdisc.gui.LyricsGuiManager getLyricsGuiManager() { return lyricsGuiManager; }

    public dev.valkdz.cdisc.lyrics.HologramPresets getHologramPresets() { return hologramPresets; }

    public void presetChanged(org.bukkit.entity.Player player) {
        if (lyricsDisplay != null) lyricsDisplay.presetChanged(player);
        if (carriedLyrics != null) carriedLyrics.presetChanged(player);
    }

    public dev.valkdz.cdisc.gui.ScreenPreferences getScreenPreferences() { return screenPreferences; }

    public dev.valkdz.cdisc.lyrics.HologramPreview getHologramPreview() { return hologramPreview; }

    public dev.valkdz.cdisc.lyrics.PresetOffers getPresetOffers() { return presetOffers; }
    public JukeboxListener getJukeboxListener() { return jukeboxListener; }
    public VoiceBackendManager getVoiceBackendManager() { return voiceBackendManager; }
    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public PortableJukeboxManager getPortableJukeboxManager() { return portableJukeboxManager; }
    public dev.valkdz.cdisc.speaker.SpeakerGroupManager getSpeakerGroupManager() { return speakerGroupManager; }
    public dev.valkdz.cdisc.gui.PairGuiManager getPairGuiManager() { return pairGuiManager; }
    public dev.valkdz.cdisc.gui.PlaylistGuiManager getPlaylistGuiManager() { return playlistGuiManager; }
    public dev.valkdz.cdisc.audio.SearchResults getSearchResults() { return searchResults; }
    public dev.valkdz.cdisc.audio.LocalMusicLibrary getLocalMusic() { return localMusic; }
    public dev.valkdz.cdisc.audio.LocalDownloader getLocalDownloader() { return localDownloader; }
    public dev.valkdz.cdisc.audio.TrackDownloader getTrackDownloader() { return trackDownloader; }
    public dev.valkdz.cdisc.youtube.PoTokenService getPoTokenService() { return poTokenService; }
    public dev.valkdz.cdisc.lyrics.LyricsService getLyricsService() { return lyricsService; }
    public dev.valkdz.cdisc.lyrics.LyricsDisplay getLyricsDisplay() { return lyricsDisplay; }

    public dev.valkdz.cdisc.lyrics.CarriedLyrics getCarriedLyrics() { return carriedLyrics; }
    public Object getPlasmoAddon() { return plasmoAddon; }

    private void greetIfFolderEmpty() {
        java.nio.file.Path folder = localMusic.root();
        if (folder == null) return;

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean empty;
            try (java.util.stream.Stream<java.nio.file.Path> entries =
                         java.nio.file.Files.list(folder)) {
                empty = entries.findAny().isEmpty();
            } catch (java.io.IOException e) {
                return;
            }
            if (!empty) return;

            getLogger().info("Nothing to play yet. Drop an .mp3 into the folder above,");
            getLogger().info("then in game: /cdisc create local:<filename> — no keys needed.");
            getLogger().info("/cdisc doctor lists every source and what each one still wants.");
        });
    }

    public void disablePlugin() { this.setEnabled(false); }

    private void suppressNoisyYoutubeLogs() {

        if (config.isYoutubeClientFailureLogging()) {
            getLogger().info("YouTube client failures will be logged in full "
                    + "(youtube.log-client-failures is on).");
            return;
        }

        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();

            String[] loggerNames = {
                    "dev.lavalink.youtube.clients.skeleton.NonMusicClient",
                    "dev.lavalink.youtube.clients.skeleton.StreamingNonMusicClient"
            };

            for (String name : loggerNames) {
                LoggerConfig loggerConfig = config.getLoggerConfig(name);
                if (!loggerConfig.getName().equals(name)) {

                    loggerConfig = new LoggerConfig(name, Level.ERROR, true);
                    config.addLogger(name, loggerConfig);
                } else {
                    loggerConfig.setLevel(Level.ERROR);
                }
            }

            ctx.updateLoggers();
        } catch (Exception e) {
            getLogger().warning("Could not suppress verbose YouTube-source logging: " + e.getMessage());
        }
    }
}
