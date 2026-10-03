package dev.valkdz.cdisc;

import dev.valkdz.cdisc.command.CDiscCommand;
import dev.valkdz.cdisc.config.Config;
import dev.valkdz.cdisc.config.MessageManager;
import dev.valkdz.cdisc.feature.portable.PortableJukeboxListener;
import dev.valkdz.cdisc.feature.portable.PortableJukeboxManager;
import dev.valkdz.cdisc.gui.PlayerActions;
import dev.valkdz.cdisc.gui.PlayerGuiListener;
import dev.valkdz.cdisc.gui.PlayerGuiManager;
import dev.valkdz.cdisc.gui.QueueGuiListener;
import dev.valkdz.cdisc.gui.QueueGuiManager;
import dev.valkdz.cdisc.integration.metrics.CDiscMetrics;
import dev.valkdz.cdisc.jukebox.JukeboxListener;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import dev.valkdz.cdisc.jukebox.TrackProgressDisplay;
import dev.valkdz.cdisc.update.UpdateChecker;
import dev.valkdz.cdisc.util.BlockNbt;
import dev.valkdz.cdisc.util.Tasks;
import dev.valkdz.cdisc.voice.VoiceBackendManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class Main extends JavaPlugin {

    private static Main instance;
    public static Main getInstance() { return instance; }

    private PlaybackManager audioPlayerManager;
    private MessageManager messageManager;
    private Config config;
    private TrackProgressDisplay trackProgressDisplay;
    private JukeboxListener jukeboxListener;
    private PlayerGuiManager playerGuiManager;
    private PlayerActions playerActions;
    private QueueGuiManager queueGuiManager;
    private dev.valkdz.cdisc.gui.LyricsGuiManager lyricsGuiManager;
    private dev.valkdz.cdisc.feature.lyrics.HologramPresets hologramPresets;
    private dev.valkdz.cdisc.gui.ScreenPreferences screenPreferences;
    private dev.valkdz.cdisc.feature.lyrics.HologramPreview hologramPreview;
    private final dev.valkdz.cdisc.feature.lyrics.PresetOffers presetOffers =
            new dev.valkdz.cdisc.feature.lyrics.PresetOffers();
    private dev.valkdz.cdisc.gui.JukeboxViewers jukeboxViewers;
    private VoiceBackendManager voiceBackendManager;
    private dev.valkdz.cdisc.feature.broadcast.BroadcastManager broadcastManager;
    private dev.valkdz.cdisc.feature.broadcast.BroadcastGui broadcastGui;
    private UpdateChecker updateChecker;
    private PortableJukeboxManager portableJukeboxManager;
    private dev.valkdz.cdisc.feature.horn.HornPlayer hornPlayer;
    private dev.valkdz.cdisc.feature.speaker.SpeakerGroupManager speakerGroupManager;
    private dev.valkdz.cdisc.gui.PairGuiManager pairGuiManager;
    private dev.valkdz.cdisc.feature.speaker.SpeakerParticles speakerParticles;
    private dev.valkdz.cdisc.gui.PlaylistGuiManager playlistGuiManager;
    private dev.valkdz.cdisc.gui.LocalConfigGuiManager localConfigGuiManager;
    private dev.valkdz.cdisc.disc.SearchResults searchResults;
    private dev.valkdz.cdisc.feature.local.LocalMusicLibrary localMusic;
    private dev.valkdz.cdisc.feature.local.LocalDownloader localDownloader;
    private dev.valkdz.cdisc.feature.local.TrackDownloader trackDownloader;
    private dev.valkdz.cdisc.jukebox.PlaybackResume playbackResume;
    private dev.valkdz.cdisc.permission.PermissionsConfig permissions;
    private dev.valkdz.cdisc.integration.worldguard.RegionGuard regionGuard;
    private dev.valkdz.cdisc.audio.source.youtube.PoTokenService poTokenService;
    private dev.valkdz.cdisc.feature.lyrics.LyricsService lyricsService;
    private dev.valkdz.cdisc.feature.lyrics.LyricsDisplay lyricsDisplay;
    private dev.valkdz.cdisc.feature.lyrics.chat.LiveChat liveChat;
    private dev.valkdz.cdisc.feature.lyrics.CarriedLyrics carriedLyrics;

    private Object plasmoAddon;

    @Override
    public void onLoad() {

        saveDefaultConfig();
        config = new Config(this);
        dev.valkdz.cdisc.util.NetProxy.prepare();
        applyProxy();
        dev.valkdz.cdisc.integration.worldguard.RegionGuard.registerFlag(this);

        // The config must already be saved: Plasmo Voice initialises registered addons
        // during its own onEnable, and reads ours there.
        plasmoAddon = dev.valkdz.cdisc.voice.plasmovoice.PlasmoVoiceActivator.tryLoad(this);
    }

    @Override
    public void onEnable() {
        instance = this;
        messageManager = new MessageManager(this);

        permissions = new dev.valkdz.cdisc.permission.PermissionsConfig(this);
        regionGuard = new dev.valkdz.cdisc.integration.worldguard.RegionGuard(this);


        // Must exist before PlaybackManager starts a session: playback asks it
        // whether the jukebox has speakers paired to it.
        speakerGroupManager = new dev.valkdz.cdisc.feature.speaker.SpeakerGroupManager(this);

        localMusic = new dev.valkdz.cdisc.feature.local.LocalMusicLibrary(this);
        if (localMusic.isEnabled()) {
            getLogger().info("Local music folder: " + localMusic.root());
            greetIfFolderEmpty();
        }

        localDownloader = new dev.valkdz.cdisc.feature.local.LocalDownloader(this);
        trackDownloader = new dev.valkdz.cdisc.feature.local.TrackDownloader(this);

        audioPlayerManager = new PlaybackManager(this);

        audioPlayerManager.startQueuePersistence();

        poTokenService = new dev.valkdz.cdisc.audio.source.youtube.PoTokenService(this);
        poTokenService.start();

        lyricsService = new dev.valkdz.cdisc.feature.lyrics.LyricsService(this);
        liveChat = new dev.valkdz.cdisc.feature.lyrics.chat.LiveChat(this);
        lyricsDisplay = new dev.valkdz.cdisc.feature.lyrics.LyricsDisplay(this, lyricsService);
        lyricsDisplay.start();

        carriedLyrics = new dev.valkdz.cdisc.feature.lyrics.CarriedLyrics(this, lyricsService);
        carriedLyrics.start();

        if (!BlockNbt.init()) {
            getLogger().warning("Block NBT access is not available on this server version. "
                    + "The jukebox spin animation may not resume and carried jukeboxes lose other plugins' block data; "
                    + "playback is unaffected.");
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

        hologramPresets = new dev.valkdz.cdisc.feature.lyrics.HologramPresets(this);
        screenPreferences = new dev.valkdz.cdisc.gui.ScreenPreferences(this);
        hologramPreview = new dev.valkdz.cdisc.feature.lyrics.HologramPreview(this);

        lyricsGuiManager = new dev.valkdz.cdisc.gui.LyricsGuiManager(this);
        getServer().getPluginManager().registerEvents(
                new dev.valkdz.cdisc.gui.LyricsGuiListener(this), this);

        queueGuiManager = new QueueGuiManager(this);
        getServer().getPluginManager().registerEvents(new QueueGuiListener(this), this);

        pairGuiManager = new dev.valkdz.cdisc.gui.PairGuiManager(this);
        getServer().getPluginManager().registerEvents(new dev.valkdz.cdisc.gui.PairGuiListener(this), this);

        playlistGuiManager = new dev.valkdz.cdisc.gui.PlaylistGuiManager(this);
        getServer().getPluginManager().registerEvents(new dev.valkdz.cdisc.gui.PlaylistGuiListener(this), this);

        localConfigGuiManager = new dev.valkdz.cdisc.gui.LocalConfigGuiManager(this);
        getServer().getPluginManager().registerEvents(new dev.valkdz.cdisc.gui.LocalConfigGuiListener(this), this);

        searchResults = new dev.valkdz.cdisc.disc.SearchResults(this);

        playbackResume = new dev.valkdz.cdisc.jukebox.PlaybackResume(this);
        getServer().getPluginManager().registerEvents(playbackResume, this);
        playbackResume.load();
        getServer().getPluginManager().registerEvents(
                new dev.valkdz.cdisc.feature.speaker.SpeakerProtectionListener(this), this);

        speakerParticles = new dev.valkdz.cdisc.feature.speaker.SpeakerParticles(this);
        speakerParticles.start();

        audioPlayerManager.setOnSessionEnded(block -> {
            playerGuiManager.forceCloseFor(block);
            lyricsDisplay.clear(block);
            dev.valkdz.cdisc.feature.speaker.SpeakerSettings.forget(block);
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

        hornPlayer = new dev.valkdz.cdisc.feature.horn.HornPlayer(this);
        getServer().getPluginManager().registerEvents(hornPlayer, this);
        hornPlayer.start();

        dev.valkdz.cdisc.integration.placeholder.PlaceholderHook.register(this);

        updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.check();

        voiceBackendManager = new VoiceBackendManager(this);
        voiceBackendManager.setup();

        if (voiceBackendManager.getBackend() != null) {
            float distance = (float) getConfig().getDouble("svc-config.distance", 32.0);
            audioPlayerManager.setVoiceBackend(voiceBackendManager.getBackend(), distance);
        }

        if (isEnabled()) {
            broadcastManager = new dev.valkdz.cdisc.feature.broadcast.BroadcastManager(this);
            broadcastGui = new dev.valkdz.cdisc.feature.broadcast.BroadcastGui(this);
            getServer().getPluginManager().registerEvents(broadcastManager, this);
            getServer().getPluginManager().registerEvents(broadcastGui, this);
            getServer().getPluginManager().registerEvents(
                    new dev.valkdz.cdisc.feature.broadcast.MicrophoneItemListener(this), this);
            broadcastManager.start();
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

        Tasks.globalLater(this, () -> {
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

        if (broadcastManager != null) {
            broadcastManager.shutdown();
        }

        if (playbackResume != null) {
            playbackResume.save();
        }
        if (trackProgressDisplay != null) {
            trackProgressDisplay.stop();
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
        if (hornPlayer != null) {
            hornPlayer.stop();
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
        if (liveChat != null) {
            liveChat.shutdown();
        }
        if (poTokenService != null) {
            poTokenService.stop();
        }
        audioPlayerManager.shutdown();
        if (jukeboxListener != null) {
            jukeboxListener.shutdown();
        }
        if (updateChecker != null) {
            updateChecker.shutdown();
        }
    }

    public void onSimpleVoiceChatReady(Object voicechatApi) {
        String categoryId = config.getSvcCategoryId();
        float distance = (float) config.getSvcDistance();

        voiceBackendManager.activateSimpleVoiceChat(voicechatApi, categoryId);
        audioPlayerManager.setVoiceBackend(voiceBackendManager.getBackend(), distance);
    }

    public PlaybackManager getAudioPlayerManager() { return audioPlayerManager; }
    public MessageManager getMessageManager() { return messageManager; }
    public Config cdiscConfig() { return config; }
    public dev.valkdz.cdisc.permission.PermissionsConfig getPermissions() { return permissions; }
    public dev.valkdz.cdisc.integration.worldguard.RegionGuard getRegionGuard() { return regionGuard; }
    public TrackProgressDisplay getTrackProgressDisplay() { return trackProgressDisplay; }
    public PlayerGuiManager getPlayerGuiManager() { return playerGuiManager; }

    public PlayerActions getPlayerActions() { return playerActions; }
    public dev.valkdz.cdisc.gui.JukeboxViewers getJukeboxViewers() { return jukeboxViewers; }
    public QueueGuiManager getQueueGuiManager() { return queueGuiManager; }

    public dev.valkdz.cdisc.gui.LyricsGuiManager getLyricsGuiManager() { return lyricsGuiManager; }

    public dev.valkdz.cdisc.feature.lyrics.HologramPresets getHologramPresets() { return hologramPresets; }

    public void presetChanged(org.bukkit.entity.Player player) {
        if (lyricsDisplay != null) lyricsDisplay.presetChanged(player);
        if (carriedLyrics != null) carriedLyrics.presetChanged(player);
    }

    public dev.valkdz.cdisc.gui.ScreenPreferences getScreenPreferences() { return screenPreferences; }

    public dev.valkdz.cdisc.feature.lyrics.HologramPreview getHologramPreview() { return hologramPreview; }

    public dev.valkdz.cdisc.feature.lyrics.PresetOffers getPresetOffers() { return presetOffers; }
    public JukeboxListener getJukeboxListener() { return jukeboxListener; }
    public VoiceBackendManager getVoiceBackendManager() { return voiceBackendManager; }
    public dev.valkdz.cdisc.feature.broadcast.BroadcastManager getBroadcastManager() { return broadcastManager; }
    public dev.valkdz.cdisc.feature.broadcast.BroadcastGui getBroadcastGui() { return broadcastGui; }
    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public PortableJukeboxManager getPortableJukeboxManager() { return portableJukeboxManager; }
    public dev.valkdz.cdisc.feature.horn.HornPlayer getHornPlayer() { return hornPlayer; }
    public dev.valkdz.cdisc.feature.speaker.SpeakerGroupManager getSpeakerGroupManager() { return speakerGroupManager; }
    public dev.valkdz.cdisc.gui.PairGuiManager getPairGuiManager() { return pairGuiManager; }
    public dev.valkdz.cdisc.gui.PlaylistGuiManager getPlaylistGuiManager() { return playlistGuiManager; }
    public dev.valkdz.cdisc.gui.LocalConfigGuiManager getLocalConfigGuiManager() { return localConfigGuiManager; }
    public dev.valkdz.cdisc.disc.SearchResults getSearchResults() { return searchResults; }
    public dev.valkdz.cdisc.feature.local.LocalMusicLibrary getLocalMusic() { return localMusic; }
    public dev.valkdz.cdisc.feature.local.LocalDownloader getLocalDownloader() { return localDownloader; }
    public dev.valkdz.cdisc.feature.local.TrackDownloader getTrackDownloader() { return trackDownloader; }
    public dev.valkdz.cdisc.audio.source.youtube.PoTokenService getPoTokenService() { return poTokenService; }
    public dev.valkdz.cdisc.feature.lyrics.LyricsService getLyricsService() { return lyricsService; }
    public dev.valkdz.cdisc.feature.lyrics.LyricsDisplay getLyricsDisplay() { return lyricsDisplay; }
    public dev.valkdz.cdisc.feature.lyrics.chat.LiveChat getLiveChat() { return liveChat; }

    public dev.valkdz.cdisc.feature.lyrics.CarriedLyrics getCarriedLyrics() { return carriedLyrics; }
    public Object getPlasmoAddon() { return plasmoAddon; }

    private void greetIfFolderEmpty() {
        java.nio.file.Path folder = localMusic.root();
        if (folder == null) return;

        Tasks.async(this, () -> {
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

    private void applyProxy() {
        dev.valkdz.cdisc.util.NetProxy.configure(config.getProxyAddress(),
                config.getProxyUsername(), config.getProxyPassword(), getLogger());
    }

    public void reloadEverything() {
        config.reload();
        applyProxy();
        localMusic.reload();
        messageManager.reload();
        permissions.reload();
        speakerGroupManager.reload();
        audioPlayerManager.reload();
        updateChecker.check();

        poTokenService.start();

        lyricsService.clearCache();
        dev.valkdz.cdisc.feature.lyrics.LyricsPrefs.forgetAll();
        lyricsDisplay.clearAll();
        lyricsDisplay.start();
    }

    public void disablePlugin() { this.setEnabled(false); }
}
