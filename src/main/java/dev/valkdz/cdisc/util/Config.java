package dev.valkdz.cdisc.util;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.voice.anchor.AnchorType;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Set;

public final class Config {

    private static final List<String> DEFAULT_YOUTUBE_CLIENTS = List.of(
            "android-vr", "ios", "music", "web", "tv", "web-embedded", "mweb");

    private final Main plugin;
    private final Tokens tokens;
    private final SourcesConfig sources;

    public Config(Main plugin) {
        this.plugin = plugin;
        this.tokens = new Tokens(plugin);
        this.sources = new SourcesConfig(plugin);
        ConfigSplitMigration.run(plugin, tokens, sources);
        dev.valkdz.cdisc.permission.PermissionsMigration.run(plugin);

        // After the split, never before: the upgrade drops keys this version no
        // longer reads, and the split still has to find the old ones to move them.
        ConfigUpgrade.runAll(plugin);
        YoutubeAutoSettings.run(plugin);
        reload();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public Tokens tokens() {
        return tokens;
    }

    public void reload() {
        plugin.reloadConfig();
        tokens.reload();
        sources.reload();
    }

    public boolean isYoutubeEnabled() {
        return sources.isEnabled("youtube", true);
    }

    public boolean isSoundcloudEnabled() {
        return sources.isEnabled("soundcloud", true);
    }

    public boolean isSpotifyEnabled() {
        return sources.isEnabled("spotify", true);
    }

    public boolean isYandexMusicEnabled() {
        return sources.isEnabled("yandex-music", true);
    }

    public boolean isVkMusicEnabled() {
        return sources.isEnabled("vk-music", true);
    }

    public boolean isTwitchEnabled() {
        return sources.isEnabled("twitch", true);
    }

    public boolean isMixcloudEnabled() {
        return sources.isEnabled("mixcloud", true);
    }

    public boolean isTiktokEnabled() {
        return sources.isEnabled("tiktok", true);
    }

    public boolean isRedditEnabled() {
        return sources.isEnabled("reddit", true);
    }

    public boolean isVimeoEnabled() {
        return sources.isEnabled("vimeo", false);
    }

    public boolean isPornhubEnabled() {
        return sources.isEnabled("pornhub", false);
    }

    public boolean isOcremixEnabled() {
        return sources.isEnabled("ocremix", false);
    }

    public boolean isSoundgasmEnabled() {
        return sources.isEnabled("soundgasm", false);
    }

    public boolean isBandcampEnabled() {
        return sources.isEnabled("bandcamp", false);
    }

    public boolean isHttpEnabled() {
        return sources.isEnabled("http", false);
    }

    public boolean isDiscordEnabled() {
        return sources.isEnabled("discord", true);
    }

    public boolean isLocalEnabled() {
        return sources.isEnabled("local", true);
    }

    public String getLocalFolder() {
        return sources.localFolder();
    }

    public Set<String> getLocalExtensions() {
        return sources.localExtensions();
    }

    public int getLocalMaxFiles() {
        return sources.localMaxFiles();
    }

    public boolean isDownloadEnabled() {
        return sources.downloadEnabled();
    }

    public long getDownloadMaxBytes() {
        return sources.downloadMaxBytes();
    }

    public int getDownloadTimeoutSeconds() {
        return sources.downloadTimeoutSeconds();
    }

    public String getSpotifyClientId() {
        return tokens.spotifyClientId();
    }

    public String getSpotifyClientSecret() {
        return tokens.spotifyClientSecret();
    }

    public String getPOtoken() {
        return tokens.youtubePoToken();
    }

    public String getVisitorData() {
        return tokens.youtubeVisitorData();
    }

    public String getYandexMusicAccessToken() {
        return tokens.yandexAccessToken();
    }

    public String getVkMusicUserToken() {
        return tokens.vkUserToken();
    }

    public boolean isYoutubeOauthEnabled() {
        return tokens.youtubeOauthEnabled();
    }

    public String getYoutubeOauthSetupDone() {
        return tokens.youtubeOauthSetupDone();
    }

    public String getYoutubeOauthRefreshToken() {
        return tokens.youtubeRefreshToken();
    }

    public int getSearchDefaultResults() {
        return sources.searchDefaultResults();
    }

    public int getSearchMaxResults(String sourceId) {
        return sources.searchMaxResults(sourceId);
    }

    public List<String> getYoutubeClients() {
        List<String> configured = sources.youtubeClients();
        return configured.isEmpty() ? DEFAULT_YOUTUBE_CLIENTS : configured;
    }

    public boolean isYoutubeClientFailureLogging() {
        return sources.youtubeLogClientFailures();
    }

    public String getYoutubeSetupGuideUrl() {
        return sources.youtubeSetupGuideUrl();
    }

    public boolean getYoutubeCustomApi() {
        return sources.youtubeFallbackApi();
    }

    public boolean isYoutubeProxyEnabled() {
        return sources.youtubeProxy();
    }

    public boolean isYoutubeSabrEnabled() {
        return sources.youtubeSabr();
    }

    public int getYoutubeSignatureTimestamp() {
        return sources.youtubeSignatureTimestamp();
    }

    public String getYoutubeWebClientVersion() {
        return sources.youtubeWebClientVersion();
    }

    public boolean isYoutubeFastCreate() {
        return sources.youtubeFastCreate();
    }

    public int getYoutubeProbeTimeoutSeconds() {
        return sources.youtubeProbeTimeoutSeconds();
    }

    public String getLanguage() {
        String value = cfg().getString("language");
        return value == null ? "auto" : value.trim();
    }

    public boolean isUpdateCheckerEnabled() {
        return cfg().getBoolean("update-checker.enabled", true);
    }

    public boolean isUpdateNotifyOps() {
        return cfg().getBoolean("update-checker.notify-ops", true);
    }

    public boolean isAutoUpdateEnabled() {
        return isUpdateCheckerEnabled() && cfg().getBoolean("update-checker.auto-update", true);
    }

    public int getUpdateIntervalHours() {
        return Math.max(0, cfg().getInt("update-checker.interval-hours", 6));
    }

    public void setYoutubeOauthSetupDone(String value) {
        tokens.setYoutubeOauthSetupDone(value);
    }

    public void setYoutubeOauthRefreshToken(String token) {
        tokens.setYoutubeRefreshToken(token);
    }

    public void setYoutubeOauthEnabled(boolean enabled) {
        tokens.setYoutubeOauthEnabled(enabled);
    }

    public void save() {
        tokens.save();
        plugin.saveConfig();
    }

    public String getRemoteCipherServerUrl() {
        return sources.remoteCipherUrl();
    }

    public String getRemoteCipherServerPassword() {
        return tokens.cipherPassword();
    }

    public String getPoTokenBackendUrl() {
        String configured = sources.poTokenBackendUrl();
        return configured.isBlank() ? tokens.poTokenBackendUrl() : configured;
    }

    public int getPoTokenTimeoutSeconds() {
        return sources.poTokenTimeoutSeconds();
    }

    public String getPoTokenBackendPassword() {
        return tokens.poTokenBackendPassword();
    }

    public String getVoiceBackend() {
        return cfg().getString("voice-backend", "auto").toLowerCase();
    }

    public String getSvcCategoryId() {
        return cfg().getString("svc-config.voicecategory.id", "cdisc");
    }

    public String getSvcCategoryName() {
        return cfg().getString("svc-config.voicecategory.name", "CDisc Music");
    }

    public String getSvcCategoryDescription() {
        return cfg().getString("svc-config.voicecategory.description", "Custom music");
    }

    public double getSvcDistance() {
        return cfg().getDouble("svc-config.distance", 32.0);
    }

    public int getBeaconRangeBoost(int level) {
        return switch (level) {
            case 1 -> cfg().getInt("beacon-range-boost.level-1", 16);
            case 2 -> cfg().getInt("beacon-range-boost.level-2", 32);
            case 3 -> cfg().getInt("beacon-range-boost.level-3", 64);
            default -> 0;
        };
    }

    public String getPlasmoSourcelineName() {
        return cfg().getString("pv-config.sourceline.name", "CDisc Music");
    }

    public AnchorType getAnchorType() {
        return AnchorType.parse(cfg().getString("sound-anchor.entity"), AnchorType.BLOCK_DISPLAY);
    }

    public boolean isPortableEnabled() {
        return cfg().getBoolean("portable-jukebox.enabled", true);
    }

    public int getPortableMaxPerPlayer() {
        return Math.max(1, cfg().getInt("portable-jukebox.max-per-player", 1));
    }

    public int getPortableParticleTicks() {
        return Math.max(20, cfg().getInt("portable-jukebox.particle-interval-ticks", 50));
    }

    public boolean isSpeakerGroupEnabled() {
        return cfg().getBoolean("speaker-group.enabled", true);
    }

    public int getSpeakerMaxDistance() {
        return Math.max(1, cfg().getInt("speaker-group.max-distance", 64));
    }

    public int getSpeakerMaxPerGroup() {
        return Math.max(2, cfg().getInt("speaker-group.max-per-group", 8));
    }

    public boolean isSpeakerProtectionEnabled() {
        return cfg().getBoolean("speaker-group.protect-members", true);
    }

    public int getVolume() {
        return cfg().getInt("volume", 80);
    }

    public boolean isLyricsEnabled() {
        return cfg().getBoolean("lyrics.enabled", true);
    }

    public boolean isLyricsDefaultOn() {
        return cfg().getBoolean("lyrics.default-on", false);
    }

    public java.util.List<String> getLyricsProviders() {
        java.util.List<String> configured = cfg().getStringList("lyrics.providers");
        return configured.isEmpty() ? java.util.List.of("lrclib", "netease") : configured;
    }

    public int getLyricsSize() {
        return Math.max(1, Math.min(10, cfg().getInt("lyrics.size", 5)));
    }

    public int getLyricsUpdateTicks() {
        return Math.max(1, cfg().getInt("lyrics.update-ticks", 4));
    }

    public double getLyricsHeight() {
        return cfg().getDouble("lyrics.height", 1.6);
    }

    public int getLyricsLinesBefore() {
        return Math.max(0, cfg().getInt("lyrics.lines-before", 1));
    }

    public int getLyricsLinesAfter() {
        return Math.max(0, cfg().getInt("lyrics.lines-after", 2));
    }

    public String getLyricsCurrentColor() {
        return colors(cfg().getString("lyrics.color.current", "&f"));
    }

    public String getLyricsOtherColor() {
        return colors(cfg().getString("lyrics.color.other", "&7"));
    }

    public int getLyricsFadeTicks() {
        return Math.max(0, Math.min(20, cfg().getInt("lyrics.animation.fade-ticks", 6)));
    }

    public boolean isLyricsSlideEnabled() {
        return cfg().getBoolean("lyrics.animation.slide", true);
    }

    public boolean isLyricsCountdownEnabled() {
        return cfg().getBoolean("lyrics.countdown.enabled", true);
    }

    public int getLyricsCountdownSeconds() {
        return Math.max(0, Math.min(10, cfg().getInt("lyrics.countdown.seconds", 3)));
    }

    public String getLyricsCountdownFilled() {
        return cfg().getString("lyrics.countdown.filled", "●");
    }

    public String getLyricsCountdownEmpty() {
        return cfg().getString("lyrics.countdown.empty", "○");
    }

    public int getLyricsLineWidth() {
        return Math.max(40, cfg().getInt("lyrics.line-width", 220));
    }

    public int getLyricsBackgroundOpacity() {
        return Math.max(0, Math.min(255, cfg().getInt("lyrics.background-opacity", 60)));
    }

    public int getLyricsTextOpacity() {
        return Math.max(0, Math.min(255, cfg().getInt("lyrics.text-opacity", 255)));
    }

    public int getLyricsBrightness() {
        int value = cfg().getInt("lyrics.brightness", -1);
        return value < 0 ? -1 : Math.min(15, value);
    }

    public boolean isLyricsShadowed() {
        return cfg().getBoolean("lyrics.shadow", false);
    }

    public boolean isLyricsSeeThrough() {
        return cfg().getBoolean("lyrics.see-through", false);
    }

    public float getLyricsViewRange() {
        return (float) Math.max(0.1, cfg().getDouble("lyrics.view-range", 1.0));
    }

    public boolean isPlayerDialogEnabled() {
        return cfg().getBoolean("player-dialog", true);
    }

    public int getPlayerDialogRefreshTicks() {
        return Math.max(1, cfg().getInt("player-dialog-refresh-ticks", 10));
    }

    public boolean isLyricsInGui() {
        return cfg().getBoolean("lyrics.gui.enabled", true);
    }

    public int getLyricsGuiLinesBefore() {
        return Math.max(0, cfg().getInt("lyrics.gui.lines-before", 2));
    }

    public int getLyricsGuiLinesAfter() {
        return Math.max(0, cfg().getInt("lyrics.gui.lines-after", 4));
    }

    public int getLyricsTimeoutSeconds() {
        return Math.max(1, cfg().getInt("lyrics.timeout-seconds", 6));
    }

    public boolean isLyricsDebug() {
        return cfg().getBoolean("lyrics.debug", false);
    }

    private static String colors(String raw) {
        return raw == null ? "" : raw.replace('&', '§');
    }
}
