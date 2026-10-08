package dev.valkdz.cdisc;

import dev.valkdz.cdisc.audio.source.youtube.PoTokenService;
import dev.valkdz.cdisc.command.CDiscCommand;
import dev.valkdz.cdisc.config.Config;
import dev.valkdz.cdisc.config.MessageManager;
import dev.valkdz.cdisc.disc.SearchResults;
import dev.valkdz.cdisc.feature.broadcast.BroadcastGui;
import dev.valkdz.cdisc.feature.broadcast.BroadcastManager;
import dev.valkdz.cdisc.feature.broadcast.MicrophoneItemListener;
import dev.valkdz.cdisc.feature.horn.HornPlayer;
import dev.valkdz.cdisc.feature.local.LocalDownloader;
import dev.valkdz.cdisc.feature.local.LocalMusicLibrary;
import dev.valkdz.cdisc.feature.local.TrackDownloader;
import dev.valkdz.cdisc.feature.lyrics.CarriedLyrics;
import dev.valkdz.cdisc.feature.lyrics.HologramPresets;
import dev.valkdz.cdisc.feature.lyrics.HologramPreview;
import dev.valkdz.cdisc.feature.lyrics.LyricsDisplay;
import dev.valkdz.cdisc.feature.lyrics.LyricsPrefs;
import dev.valkdz.cdisc.feature.lyrics.LyricsService;
import dev.valkdz.cdisc.feature.lyrics.PresetOffers;
import dev.valkdz.cdisc.feature.lyrics.chat.LiveChat;
import dev.valkdz.cdisc.feature.portable.PortableJukeboxListener;
import dev.valkdz.cdisc.feature.portable.PortableJukeboxManager;
import dev.valkdz.cdisc.feature.speaker.SpeakerGroupManager;
import dev.valkdz.cdisc.feature.speaker.SpeakerParticles;
import dev.valkdz.cdisc.feature.speaker.SpeakerProtectionListener;
import dev.valkdz.cdisc.feature.speaker.SpeakerSettings;
import dev.valkdz.cdisc.gui.JukeboxViewers;
import dev.valkdz.cdisc.gui.LocalConfigGuiListener;
import dev.valkdz.cdisc.gui.LocalConfigGuiManager;
import dev.valkdz.cdisc.gui.LyricsGuiListener;
import dev.valkdz.cdisc.gui.LyricsGuiManager;
import dev.valkdz.cdisc.gui.PairGuiListener;
import dev.valkdz.cdisc.gui.PairGuiManager;
import dev.valkdz.cdisc.gui.PlayerActions;
import dev.valkdz.cdisc.gui.PlayerGuiListener;
import dev.valkdz.cdisc.gui.PlayerGuiManager;
import dev.valkdz.cdisc.gui.PlaylistGuiListener;
import dev.valkdz.cdisc.gui.PlaylistGuiManager;
import dev.valkdz.cdisc.gui.QueueGuiListener;
import dev.valkdz.cdisc.gui.QueueGuiManager;
import dev.valkdz.cdisc.gui.ScreenPreferences;
import dev.valkdz.cdisc.integration.metrics.CDiscMetrics;
import dev.valkdz.cdisc.integration.placeholder.PlaceholderHook;
import dev.valkdz.cdisc.integration.worldguard.RegionGuard;
import dev.valkdz.cdisc.jukebox.JukeboxListener;
import dev.valkdz.cdisc.jukebox.LockedDiscGuard;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import dev.valkdz.cdisc.jukebox.PlaybackResume;
import dev.valkdz.cdisc.jukebox.TrackProgressDisplay;
import dev.valkdz.cdisc.permission.PermissionsConfig;
import dev.valkdz.cdisc.update.UpdateChecker;
import dev.valkdz.cdisc.util.BlockNbt;
import dev.valkdz.cdisc.util.NetProxy;
import dev.valkdz.cdisc.util.Tasks;
import dev.valkdz.cdisc.voice.VoiceBackendManager;
import dev.valkdz.cdisc.voice.plasmovoice.PlasmoVoiceActivator;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
    private LyricsGuiManager lyricsGuiManager;
    private HologramPresets hologramPresets;
    private ScreenPreferences screenPreferences;
    private HologramPreview hologramPreview;
    private final PresetOffers presetOffers = new PresetOffers();
    private JukeboxViewers jukeboxViewers;
    private VoiceBackendManager voiceBackendManager;
    private BroadcastManager broadcastManager;
    private BroadcastGui broadcastGui;
    private UpdateChecker updateChecker;
    private PortableJukeboxManager portableJukeboxManager;
    private HornPlayer hornPlayer;
    private SpeakerGroupManager speakerGroupManager;
    private PairGuiManager pairGuiManager;
    private SpeakerParticles speakerParticles;
    private PlaylistGuiManager playlistGuiManager;
    private LocalConfigGuiManager localConfigGuiManager;
    private SearchResults searchResults;
    private LocalMusicLibrary localMusic;
    private LocalDownloader localDownloader;
    private TrackDownloader trackDownloader;
    private PlaybackResume playbackResume;
    private PermissionsConfig permissions;
    private RegionGuard regionGuard;
    private PoTokenService poTokenService;
    private LyricsService lyricsService;
    private LyricsDisplay lyricsDisplay;
    private LiveChat liveChat;
    private CarriedLyrics carriedLyrics;

    private Object plasmoAddon;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        config = new Config(this);
        NetProxy.prepare();
        applyProxy();
        RegionGuard.registerFlag(this);

        // The config must already be saved: Plasmo Voice initialises registered addons
        // during its own onEnable, and reads ours there.
        plasmoAddon = PlasmoVoiceActivator.tryLoad(this);
    }

    @Override
    public void onEnable() {
        instance = this;
        messageManager = new MessageManager(this);

        permissions = new PermissionsConfig(this);
        regionGuard = new RegionGuard(this);

        // Must exist before PlaybackManager starts a session: playback asks it
        // whether the jukebox has speakers paired to it.
        speakerGroupManager = new SpeakerGroupManager(this);

        localMusic = new LocalMusicLibrary(this);
        if (localMusic.isEnabled()) {
            getLogger().info("Local music folder: " + localMusic.root());
            greetIfFolderEmpty();
        }

        localDownloader = new LocalDownloader(this);
        trackDownloader = new TrackDownloader(this);

        audioPlayerManager = new PlaybackManager(this);
        audioPlayerManager.startQueuePersistence();

        poTokenService = new PoTokenService(this);
        poTokenService.start();

        lyricsService = new LyricsService(this);
        liveChat = new LiveChat(this);
        lyricsDisplay = new LyricsDisplay(this, lyricsService);
        lyricsDisplay.start();

        carriedLyrics = new CarriedLyrics(this, lyricsService);
        carriedLyrics.start();

        if (!BlockNbt.init()) {
            getLogger().warning("Block NBT access is not available on this server version. "
                    + "The jukebox spin animation may not resume and carried jukeboxes lose other plugins' block data; "
                    + "playback is unaffected.");
        }

        CDiscCommand command = new CDiscCommand(this);
        PluginCommand cdisc = Objects.requireNonNull(getCommand("cdisc"));
        cdisc.setExecutor(command);
        cdisc.setTabCompleter(command);

        jukeboxListener = new JukeboxListener(this);
        listen(jukeboxListener);

        trackProgressDisplay = new TrackProgressDisplay(this);
        listen(trackProgressDisplay);
        trackProgressDisplay.start();

        jukeboxViewers = new JukeboxViewers();
        playerGuiManager = new PlayerGuiManager(this);
        playerActions = new PlayerActions(this);
        listen(new PlayerGuiListener(this));
        playerGuiManager.start();

        hologramPresets = new HologramPresets(this);
        screenPreferences = new ScreenPreferences(this);
        hologramPreview = new HologramPreview(this);

        lyricsGuiManager = new LyricsGuiManager(this);
        listen(new LyricsGuiListener(this));

        queueGuiManager = new QueueGuiManager(this);
        listen(new QueueGuiListener(this), new LockedDiscGuard(this));

        pairGuiManager = new PairGuiManager(this);
        listen(new PairGuiListener(this));

        playlistGuiManager = new PlaylistGuiManager(this);
        listen(new PlaylistGuiListener(this));

        localConfigGuiManager = new LocalConfigGuiManager(this);
        listen(new LocalConfigGuiListener(this));

        searchResults = new SearchResults(this);

        playbackResume = new PlaybackResume(this);
        listen(playbackResume);
        playbackResume.load();
        listen(new SpeakerProtectionListener(this));

        speakerParticles = new SpeakerParticles(this);
        speakerParticles.start();

        audioPlayerManager.setOnSessionEnded(block -> {
            playerGuiManager.forceCloseFor(block);
            lyricsDisplay.clear(block);
            SpeakerSettings.forget(block);
            queueGuiManager.forceCloseFor(block);
            jukeboxViewers.clear(block);
            jukeboxListener.clearControlled(block);
            ifSet(portableJukeboxManager, m -> m.onSessionEnded(block));
        });

        portableJukeboxManager = new PortableJukeboxManager(this);
        listen(new PortableJukeboxListener(this));
        portableJukeboxManager.start();

        hornPlayer = new HornPlayer(this);
        listen(hornPlayer);
        hornPlayer.start();

        PlaceholderHook.register(this);

        updateChecker = new UpdateChecker(this);
        listen(updateChecker);
        updateChecker.check();

        voiceBackendManager = new VoiceBackendManager(this);
        voiceBackendManager.setup();
        // disablePlugin() has already run onDisable; scheduling anything past here would throw.
        if (!isEnabled()) return;

        if (voiceBackendManager.getBackend() != null) {
            audioPlayerManager.setVoiceBackend(voiceBackendManager.getBackend(), (float) config.getSvcDistance());
        }

        broadcastManager = new BroadcastManager(this);
        broadcastGui = new BroadcastGui(this);
        listen(broadcastManager, broadcastGui, new MicrophoneItemListener(this));
        broadcastManager.start();

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

        CDiscMetrics.start(this);
    }

    @Override
    public void onDisable() {
        ifSet(localDownloader, LocalDownloader::shutdown);
        ifSet(broadcastManager, BroadcastManager::shutdown);
        ifSet(playbackResume, PlaybackResume::save);
        ifSet(speakerGroupManager, SpeakerGroupManager::flush);
        ifSet(localMusic, LocalMusicLibrary::flushSettings);
        ifSet(trackProgressDisplay, d -> { d.stop(); d.clearAll(); });
        ifSet(playerGuiManager, PlayerGuiManager::stop);
        ifSet(playlistGuiManager, PlaylistGuiManager::stop);
        ifSet(speakerParticles, SpeakerParticles::stop);
        ifSet(portableJukeboxManager, PortableJukeboxManager::stop);
        ifSet(hornPlayer, HornPlayer::stop);
        ifSet(hologramPreview, HologramPreview::hideAll);
        ifSet(hologramPresets, HologramPresets::saveNow);
        ifSet(screenPreferences, ScreenPreferences::saveNow);
        ifSet(carriedLyrics, l -> { l.stop(); l.clearAll(); });
        ifSet(lyricsDisplay, l -> { l.stop(); l.clearAll(); });
        ifSet(lyricsService, LyricsService::shutdown);
        ifSet(liveChat, LiveChat::shutdown);
        ifSet(poTokenService, PoTokenService::stop);
        ifSet(audioPlayerManager, PlaybackManager::shutdown);
        ifSet(jukeboxListener, JukeboxListener::shutdown);
        ifSet(updateChecker, UpdateChecker::shutdown);
    }

    private void listen(Listener... listeners) {
        for (Listener listener : listeners) getServer().getPluginManager().registerEvents(listener, this);
    }

    private static <T> void ifSet(T component, Consumer<T> action) {
        if (component != null) action.accept(component);
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
    public PermissionsConfig getPermissions() { return permissions; }
    public RegionGuard getRegionGuard() { return regionGuard; }
    public TrackProgressDisplay getTrackProgressDisplay() { return trackProgressDisplay; }
    public PlayerGuiManager getPlayerGuiManager() { return playerGuiManager; }

    public PlayerActions getPlayerActions() { return playerActions; }
    public JukeboxViewers getJukeboxViewers() { return jukeboxViewers; }
    public QueueGuiManager getQueueGuiManager() { return queueGuiManager; }

    public LyricsGuiManager getLyricsGuiManager() { return lyricsGuiManager; }

    public HologramPresets getHologramPresets() { return hologramPresets; }

    public void presetChanged(Player player) {
        ifSet(lyricsDisplay, d -> d.presetChanged(player));
        ifSet(carriedLyrics, c -> c.presetChanged(player));
    }

    public ScreenPreferences getScreenPreferences() { return screenPreferences; }

    public HologramPreview getHologramPreview() { return hologramPreview; }

    public PresetOffers getPresetOffers() { return presetOffers; }
    public JukeboxListener getJukeboxListener() { return jukeboxListener; }
    public VoiceBackendManager getVoiceBackendManager() { return voiceBackendManager; }
    public BroadcastManager getBroadcastManager() { return broadcastManager; }
    public BroadcastGui getBroadcastGui() { return broadcastGui; }
    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public PortableJukeboxManager getPortableJukeboxManager() { return portableJukeboxManager; }
    public HornPlayer getHornPlayer() { return hornPlayer; }
    public SpeakerGroupManager getSpeakerGroupManager() { return speakerGroupManager; }
    public PairGuiManager getPairGuiManager() { return pairGuiManager; }
    public PlaylistGuiManager getPlaylistGuiManager() { return playlistGuiManager; }
    public LocalConfigGuiManager getLocalConfigGuiManager() { return localConfigGuiManager; }
    public SearchResults getSearchResults() { return searchResults; }
    public LocalMusicLibrary getLocalMusic() { return localMusic; }
    public LocalDownloader getLocalDownloader() { return localDownloader; }
    public TrackDownloader getTrackDownloader() { return trackDownloader; }
    public PoTokenService getPoTokenService() { return poTokenService; }
    public LyricsService getLyricsService() { return lyricsService; }
    public LyricsDisplay getLyricsDisplay() { return lyricsDisplay; }
    public LiveChat getLiveChat() { return liveChat; }

    public CarriedLyrics getCarriedLyrics() { return carriedLyrics; }
    public Object getPlasmoAddon() { return plasmoAddon; }

    private void greetIfFolderEmpty() {
        Path folder = localMusic.root();
        if (folder == null) return;

        Tasks.async(this, () -> {
            boolean empty;
            try (Stream<Path> entries =
                         Files.list(folder)) {
                empty = entries.findAny().isEmpty();
            } catch (IOException e) {
                return;
            }
            if (!empty) return;

            getLogger().info("Nothing to play yet. Drop an .mp3 into the folder above,");
            getLogger().info("then in game: /cdisc create local:<filename> — no keys needed.");
            getLogger().info("/cdisc doctor lists every source and what each one still wants.");
        });
    }

    private void applyProxy() {
        NetProxy.configure(config.getProxyAddress(),
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
        LyricsPrefs.forgetAll();
        lyricsDisplay.clearAll();
        lyricsDisplay.start();
    }

    public void disablePlugin() { this.setEnabled(false); }
}
