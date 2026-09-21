package dev.valkdz.cdisc.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.engine.NowPlayingBroadcaster;
import dev.valkdz.cdisc.audio.engine.TrackLoader;
import dev.valkdz.cdisc.audio.queue.DiscQueue;
import dev.valkdz.cdisc.audio.queue.QueueStore;
import dev.valkdz.cdisc.audio.queue.RepeatMode;
import dev.valkdz.cdisc.horn.GoatHorns;
import dev.valkdz.cdisc.metrics.CDiscMetrics;
import dev.valkdz.cdisc.speaker.SpeakerGroup;
import dev.valkdz.cdisc.speaker.SpeakerSettings;
import dev.valkdz.cdisc.util.Config;
import dev.valkdz.cdisc.util.ItemUtils;
import dev.valkdz.cdisc.util.TimeUtils;
import dev.valkdz.cdisc.voice.VoiceBackend;
import dev.valkdz.cdisc.voice.VoiceSession;
import dev.valkdz.cdisc.voice.anchor.AnchorManager;
import dev.valkdz.cdisc.voice.anchor.SoundAnchor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class LavaPlayerManager {
    private final Main plugin;
    private final TrackLoader trackLoader;
    private final NowPlayingBroadcaster broadcaster;

    // Track-end callbacks read both of these on a LavaPlayer thread while the main thread writes them.
    private final Map<Block, List<AudioSession>> sessions = new ConcurrentHashMap<>();
    private final Map<Block, Integer> generation = new ConcurrentHashMap<>();

    private final Map<Block, SoundAnchor> anchors = new ConcurrentHashMap<>();

    // Callbacks run long after the track was loaded, so they hold a ref rather
    // than the Block they were created with.
    private final Map<Block, BlockRef> blockRefs = new ConcurrentHashMap<>();
    private final AnchorManager anchorManager;

    private final Map<Block, RepeatMode> repeatModes = new ConcurrentHashMap<>();

    private final Map<Block, DiscQueue> queues = new ConcurrentHashMap<>();

    private final Map<Block, Integer> savedRevisions = new ConcurrentHashMap<>();

    private final QueueStore queueStore;
    private org.bukkit.scheduler.BukkitTask queueSaveTask;

    private final java.util.Set<Block> shuffled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Map<Block, Integer> beaconRangeLevel = new ConcurrentHashMap<>();

    private VoiceBackend voiceBackend;
    private float distance;

    private Consumer<Block> onSessionEnded;

    public LavaPlayerManager(Main plugin) {
        this.plugin = plugin;
        this.trackLoader = new TrackLoader(plugin);
        this.broadcaster = new NowPlayingBroadcaster(plugin);
        this.anchorManager = new AnchorManager(plugin);
        this.queueStore = new QueueStore(plugin);
    }

    public void startQueuePersistence() {
        queueStore.load();
        queueSaveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> saveQueues(true), 100L, 40L);
    }

    public void saveQueues(boolean async) {
        evictSpentQueues();
        boolean migrated = queueStore.migratePending();
        if (!migrated && !queuesChanged()) return;

        queueStore.save(queues, async);

        savedRevisions.clear();
        for (Map.Entry<Block, DiscQueue> entry : queues.entrySet()) {
            savedRevisions.put(entry.getKey(), entry.getValue().getRevision());
        }
    }

    private boolean queuesChanged() {
        if (savedRevisions.size() != queues.size()) return true;
        for (Map.Entry<Block, DiscQueue> entry : queues.entrySet()) {
            Integer saved = savedRevisions.get(entry.getKey());
            if (saved == null || saved != entry.getValue().getRevision()) return true;
        }
        return false;
    }

    private void evictSpentQueues() {
        queues.entrySet().removeIf(entry -> {
            DiscQueue queue = entry.getValue();
            if (!queue.isEmpty() || queue.getCurrentIndex() >= 0) return false;
            if (queue.getPolicy() != dev.valkdz.cdisc.audio.queue.PlayedPolicy.NOTHING) return false;

            Block block = entry.getKey();
            if (hasActiveSession(block)) return false;

            if (plugin.getJukeboxViewers() != null && plugin.getJukeboxViewers().isClaimed(block)) return false;
            if (plugin.getQueueGuiManager() != null && plugin.getQueueGuiManager().hasViewer(block)) return false;

            // Its copy inside the jukebox outlives this map, so let it go only once that is gone.
            if (!queueStore.clearBlock(block)) return false;

            savedRevisions.remove(block);
            return true;
        });
    }

    private static Block here(BlockRef ref, Block fallback) {
        return ref == null ? fallback : ref.get();
    }

    private static final class BlockRef {
        private volatile Block block;

        BlockRef(Block block) {
            this.block = block;
        }

        Block get() {
            return block;
        }
    }

    public AnchorManager getAnchorManager() {
        return anchorManager;
    }

    private org.bukkit.Location audibleOrigin(Block block) {
        SoundAnchor anchor = anchors.get(block);
        if (anchor != null && anchor.isAlive()) {
            return anchor.entity().getLocation();
        }
        return block.getLocation().add(0.5, 0.5, 0.5);
    }

    public SoundAnchor getAnchor(Block block) {
        return anchors.get(block);
    }

    private void removeAnchor(Block block) {
        SoundAnchor anchor = anchors.remove(block);
        if (anchor != null) anchor.remove();
    }

    private record SpeakerOutput(SoundAnchor anchor, VoiceSession session) {
    }

    private final Map<Block, Map<Block, SpeakerOutput>> speakerOutputs = new ConcurrentHashMap<>();

    private void attachSpeakers(Block main, AudioSession session) {
        SpeakerGroup group = plugin.getSpeakerGroupManager().groupAt(main);
        if (group == null || !group.isMain(main.getLocation())) return;

        Map<Block, SpeakerOutput> attached = new ConcurrentHashMap<>();
        for (org.bukkit.Location location : group.speakers()) {
            org.bukkit.World world = location.getWorld();
            if (world == null) continue;
            if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) continue;

            Block speaker = location.getBlock();
            if (speaker.getType() != Material.JUKEBOX) {

                plugin.getSpeakerGroupManager().removeSpeaker(group, speaker);
                continue;
            }

            SoundAnchor anchor = anchorManager.createFor(speaker);
            VoiceSession output = voiceBackend.createSyncedEntitySession(
                    anchor.entity(), effectiveDistance(main));
            if (output == null) {
                anchor.remove();
                continue;
            }
            session.addSpeakerOutput(output);
            attached.put(speaker, new SpeakerOutput(anchor, output));
        }

        if (!attached.isEmpty()) speakerOutputs.put(main, attached);
    }

    private void detachSpeakers(Block main) {
        Map<Block, SpeakerOutput> attached = speakerOutputs.remove(main);
        if (attached == null) return;
        attached.values().forEach(output -> output.anchor().remove());
    }

    public void attachSpeakerLive(Block main, Block speaker) {
        List<AudioSession> list = sessions.get(main);
        if (list == null || list.isEmpty() || voiceBackend == null) return;
        if (speaker.getType() != Material.JUKEBOX) return;

        Map<Block, SpeakerOutput> attached =
                speakerOutputs.computeIfAbsent(main, k -> new ConcurrentHashMap<>());
        if (attached.containsKey(speaker)) return;

        SoundAnchor anchor = anchorManager.createFor(speaker);
        VoiceSession output = voiceBackend.createSyncedEntitySession(
                anchor.entity(), effectiveDistance(main));
        if (output == null) {
            anchor.remove();
            return;
        }
        AudioSession session = list.get(0);
        session.addSpeakerOutput(output);

        session.getVoiceSession().setSynced(true);
        attached.put(speaker, new SpeakerOutput(anchor, output));

        applySpeakerSettings(main);
    }

    public void applySpeakerSettings(Block main) {
        List<AudioSession> list = sessions.get(main);
        if (list == null || list.isEmpty()) return;

        SpeakerSettings mainSettings = SpeakerSettings.of(main);
        for (AudioSession session : list) {
            session.getVoiceSession().applySpeakerSettings(mainSettings);
        }
        applyCarrierVolume(main, mainSettings);

        Map<Block, SpeakerOutput> attached = speakerOutputs.get(main);
        if (attached == null) return;
        for (Map.Entry<Block, SpeakerOutput> entry : attached.entrySet()) {
            entry.getValue().session().applySpeakerSettings(SpeakerSettings.of(entry.getKey()));
        }
    }

    public void applyCarrierVolume(Block main, SpeakerSettings settings) {
        if (plugin.getPortableJukeboxManager() == null) return;

        List<AudioSession> list = sessions.get(main);
        if (list == null || list.isEmpty()) return;

        dev.valkdz.cdisc.portable.PortableJukeboxManager.Carry carry =
                plugin.getPortableJukeboxManager().carryOfBlock(main);
        if (carry == null) return;

        Player carrier = Bukkit.getPlayer(carry.carrier());
        if (carrier == null) return;

        int volume = dev.valkdz.cdisc.util.PlayerPrefs
                .effectiveLocalVolume(carrier, settings.volume());
        for (AudioSession session : list) {
            session.getVoiceSession().setDirectVolume(volume);
        }
    }

    public void applyCarrierVolume(Block main) {
        applyCarrierVolume(main, SpeakerSettings.of(main));
    }

    public void applySpeakerSettings(Block main, Block speaker) {
        if (main.equals(speaker)) {
            List<AudioSession> list = sessions.get(main);
            if (list == null) return;
            SpeakerSettings settings = SpeakerSettings.of(main);
            for (AudioSession session : list) {
                session.getVoiceSession().applySpeakerSettings(settings);
            }
            return;
        }

        Map<Block, SpeakerOutput> attached = speakerOutputs.get(main);
        if (attached == null) return;
        SpeakerOutput output = attached.get(speaker);
        if (output != null) {
            output.session().applySpeakerSettings(SpeakerSettings.of(speaker));
        }
    }

    public void detachSpeakerLive(Block main, Block speaker) {
        Map<Block, SpeakerOutput> attached = speakerOutputs.get(main);
        if (attached == null) return;
        SpeakerOutput output = attached.remove(speaker);
        if (output == null) return;

        List<AudioSession> list = sessions.get(main);
        if (list != null && !list.isEmpty()) {
            AudioSession session = list.get(0);
            session.removeSpeakerOutput(output.session());
        }
        output.anchor().remove();
        if (attached.isEmpty()) speakerOutputs.remove(main);
    }

    public void setVoiceBackend(VoiceBackend backend, float distance) {
        this.voiceBackend = backend;
        this.distance = distance;

        trackLoader.setPcmOutput(backend != null && backend.wantsPcm());
    }

    public VoiceSession createFollowingSession(org.bukkit.entity.Entity anchor, float distance) {
        return voiceBackend == null ? null : voiceBackend.createEntitySession(anchor, distance);
    }

    public boolean hasVoiceBackend() {
        return voiceBackend != null;
    }

    public TrackLoader getTrackLoader() {
        return trackLoader;
    }

    public void createDisc(Player player, ItemStack item, String query) {
        SearchQuery parsed = SearchQuery.parse(query);

        if (LocalMusicLibrary.isLocalQuery(parsed.text())) {
            createLocalDisc(player, item, parsed);
            return;
        }

        String resolved = trackLoader.resolveQuery(parsed.text());
        if (resolved == null) {
            player.sendMessage("§c" + plugin.getMessageManager().get(player, "lavaplayer.track.invalid_query"));
            return;
        }

        if (TrackLoader.isSearch(resolved)) {
            int ceiling = plugin.cdiscConfig().getSearchMaxResults(
                    TrackLoader.sourceIdOf(parsed.text()));
            int wanted = parsed.requestedResults() == SearchQuery.UNSET
                    ? plugin.cdiscConfig().getSearchDefaultResults()
                    : parsed.requestedResults();
            search(player, item, parsed.text(), resolved, Math.min(wanted, ceiling));
            return;
        }

        loadForDisc(player, item, parsed.text(), resolved);
    }

    private void createLocalDisc(Player player, ItemStack item, SearchQuery parsed) {
        LocalMusicLibrary library = plugin.getLocalMusic();
        if (!library.isEnabled()) {
            player.sendMessage("§c" + plugin.getMessageManager().get(player, "local.disabled"));
            return;
        }

        String name = LocalMusicLibrary.stripPrefix(parsed.text());
        String exact = library.find(name);
        if (exact != null) {
            writeLocalDisc(player, item, exact);
            return;
        }

        int ceiling = plugin.cdiscConfig().getSearchMaxResults("local");
        int wanted = parsed.requestedResults() == SearchQuery.UNSET
                ? plugin.cdiscConfig().getSearchDefaultResults()
                : parsed.requestedResults();

        List<String> hits = library.search(name, Math.min(wanted, ceiling));
        if (hits.isEmpty()) {
            String key = library.index().isEmpty() ? "local.empty" : "local.not_found";
            player.sendMessage("§c" + plugin.getMessageManager().get(player, key));
            return;
        }
        if (hits.size() == 1) {
            writeLocalDisc(player, item, hits.get(0));
            return;
        }
        showLocalResults(player, parsed.text(), hits);
    }

    private void writeLocalDisc(Player player, ItemStack item, String relative) {
        File file = plugin.getLocalMusic().fileFor(relative);
        if (file == null) {

            player.sendMessage("§c" + plugin.getMessageManager().get(player, "local.not_found"));
            return;
        }
        loadForDisc(player, item, LocalMusicLibrary.PREFIX + relative, file.getAbsolutePath());
    }

    private void showLocalResults(Player player, String query, List<String> relatives) {
        LocalMusicLibrary library = plugin.getLocalMusic();
        AudioTrack[] loaded = new AudioTrack[relatives.size()];
        AtomicInteger remaining = new AtomicInteger(relatives.size());

        Runnable finish = () -> {
            List<SearchResults.Entry> entries = new ArrayList<>();
            for (int i = 0; i < loaded.length; i++) {
                if (loaded[i] == null) continue;
                String relative = relatives.get(i);
                entries.add(new SearchResults.Entry(
                        loaded[i],
                        library.titleFor(relative, loaded[i].getInfo().title),
                        LocalMusicLibrary.PREFIX + relative));
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (entries.isEmpty()) {
                    player.sendMessage("§c" + plugin.getMessageManager()
                            .get(player, "lavaplayer.track.notfound"));
                } else {
                    plugin.getSearchResults().show(player, query, entries);
                }
            });
        };

        for (int i = 0; i < relatives.size(); i++) {
            int slot = i;
            File file = plugin.getLocalMusic().fileFor(relatives.get(i));
            if (file == null) {
                if (remaining.decrementAndGet() == 0) finish.run();
                continue;
            }
            trackLoader.loadItem(file.getAbsolutePath(), new AudioLoadResultHandler() {
                @Override public void trackLoaded(AudioTrack track) {
                    loaded[slot] = track;
                    done();
                }

                @Override public void playlistLoaded(AudioPlaylist playlist) {
                    if (!playlist.getTracks().isEmpty()) loaded[slot] = playlist.getTracks().get(0);
                    done();
                }

                @Override public void noMatches() {
                    done();
                }

                @Override public void loadFailed(FriendlyException e) {
                    done();
                }

                private void done() {
                    if (remaining.decrementAndGet() == 0) finish.run();
                }
            });
        }
    }

    private void loadForDisc(Player player, ItemStack item, String query, String resolved) {
        trackLoader.loadItem(resolved, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) {
                writeToDisc(player, item, track, query, resolved);
            }

            @Override public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) return;
                trackLoaded(playlist.getTracks().get(0));

                int rest = playlist.getTracks().size() - 1;
                if (rest > 0) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            player.sendMessage("§e" + plugin.getMessageManager()
                                    .get(player, "playlist.truncated", String.valueOf(rest))));
                }
            }

            @Override public void noMatches() {

                String hint = LoadDiagnosis.explain(plugin, player, query);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§c" + plugin.getMessageManager().get(player, "lavaplayer.track.notfound"));
                    if (hint != null) player.sendMessage("§7" + hint);
                });
            }

            @Override public void loadFailed(FriendlyException e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage("§c" + plugin.getMessageManager()
                                .get(player, "lavaplayer.track.error", String.valueOf(e.getMessage()))));
            }
        });
    }

    private final java.util.concurrent.atomic.AtomicLong lastOperatorWarning =
            new java.util.concurrent.atomic.AtomicLong();

    private static final long OPERATOR_WARNING_GAP_MS = 10 * 60 * 1000L;

    private void warnOperators(AudioTrack track) {
        if (!trackLoader.isYoutubeIdentifier(track.getInfo().uri)) return;

        Config config = plugin.cdiscConfig();
        if (config.getYoutubeCustomApi()) return;
        if (!config.getPOtoken().isEmpty() && !config.getVisitorData().isEmpty()) return;

        long now = System.currentTimeMillis();
        long last = lastOperatorWarning.get();
        if (now - last < OPERATOR_WARNING_GAP_MS) return;
        if (!lastOperatorWarning.compareAndSet(last, now)) return;

        String url = config.getYoutubeSetupGuideUrl();

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player op : Bukkit.getOnlinePlayers()) {
                if (!dev.valkdz.cdisc.permission.Perms.isAdmin(op)) continue;

                op.sendMessage("§c" + plugin.getMessageManager()
                        .get(op, "diagnose.youtube_operator"));
                op.sendMessage("§7" + plugin.getMessageManager()
                        .get(op, url.isEmpty()
                                ? "diagnose.youtube_operator_where"
                                : "diagnose.youtube_operator_link", localised(url, op)));
            }
        });
    }

    private String localised(String url, Player op) {
        if (!plugin.getMessageManager().localeFor(op).startsWith("ru")) return url;
        if (url.contains("/ru/") || !url.contains("/#/")) return url;
        return url.replace("/#/", "/ru/#/");
    }

    private void search(Player player, ItemStack item, String query, String resolved, int limit) {
        trackLoader.loadItem(resolved, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) {

                writeToDisc(player, item, track, addressOf(track, query), resolved);
            }

            @Override public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrack> results = playlist.getTracks();
                if (results.isEmpty()) {
                    noMatches();
                    return;
                }
                if (results.size() == 1) {
                    trackLoaded(results.get(0));
                    return;
                }

                Offer offer = offerable(player, results, limit);
                if (offer.tracks().isEmpty()) {
                    refuse(player, offer.refusal());
                    return;
                }

                List<SearchResults.Entry> shown = offer.tracks().stream()
                        .map(found -> new SearchResults.Entry(
                                found, found.getInfo().title, addressOf(found, query)))
                        .toList();
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.getSearchResults().show(player, query, shown));
            }

            @Override public void noMatches() {

                String hint = LoadDiagnosis.explain(plugin, player, query);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§c" + plugin.getMessageManager().get(player, "lavaplayer.track.notfound"));
                    if (hint != null) player.sendMessage("§7" + hint);
                });
            }

            @Override public void loadFailed(FriendlyException e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage("§c" + plugin.getMessageManager()
                                .get(player, "lavaplayer.track.error", String.valueOf(e.getMessage()))));
            }
        });
    }

    private record Offer(List<AudioTrack> tracks, String refusal) {
    }

    private Offer offerable(Player player, List<AudioTrack> results, int limit) {
        List<AudioTrack> allowed = new ArrayList<>();
        String refusal = null;

        for (AudioTrack found : results) {
            String why = plugin.getPermissions().trackRejection(
                    player, found.getInfo().isStream, found.getInfo().length);
            if (why == null) {
                allowed.add(found);
                if (allowed.size() >= limit) break;
            } else if (refusal == null) {
                refusal = why;
            }
        }
        return new Offer(allowed, refusal);
    }

    private void refuse(Player player, String refusal) {
        String key = refusal == null ? "lavaplayer.track.notfound"
                : refusal.equals("perms.track_too_long") ? "perms.search_all_too_long"
                : refusal;
        String limit = TimeUtils.format(plugin.getPermissions().maxTrackSeconds() * 1000L);
        Bukkit.getScheduler().runTask(plugin, () ->
                player.sendMessage("§c" + plugin.getMessageManager().get(player, key, limit)));
    }

    public void writePickedTrack(Player player, ItemStack item, AudioTrack track, String address) {
        writeToDisc(player, item, track, address, address);
    }

    private String addressOf(AudioTrack track, String query) {

        String local = plugin.getLocalMusic().addressOf(track);
        if (local != null) return local;

        String uri = track.getInfo().uri;
        return uri == null || uri.isBlank() ? query : uri;
    }

    private static boolean usable(String tag) {
        return tag != null && !tag.isBlank()
                && !tag.equals(MediaContainerDetection.UNKNOWN_TITLE)
                && !tag.equals(MediaContainerDetection.UNKNOWN_ARTIST);
    }

    private void writeToDisc(Player player, ItemStack item, AudioTrack track,
                             String query, String resolved) {

        if (GoatHorns.isHorn(item)) {
            String refusal = GoatHorns.refusal(plugin, player, track.getInfo());
            if (refusal != null) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§c" + refusal));
                return;
            }
        }

        String rejection = LocalMusicLibrary.isLocalQuery(query) || GoatHorns.isHorn(item) ? null
                : plugin.getPermissions().trackRejection(
                        player, track.getInfo().isStream, track.getInfo().length);
        if (rejection != null) {
            int maxSeconds = plugin.getPermissions().maxTrackSeconds();
            Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage("§c" + plugin.getMessageManager()
                            .get(player, rejection, TimeUtils.format(maxSeconds * 1000L))));
            return;
        }

        String twitchChannel = trackLoader.twitchChannelOf(query);

        String localFile = LocalMusicLibrary.stripPrefix(query);

        String discordFile = trackLoader.discordTitleOf(query);

        String author;
        if (twitchChannel != null) {
            author = "Twitch";
        } else if (discordFile != null) {
            author = usable(track.getInfo().author)
                    ? track.getInfo().author : DiscordSource.ARTIST;
        } else {
            author = track.getInfo().author != null ? track.getInfo().author : "Unknown";
        }

        String title;
        if (twitchChannel != null) {
            title = twitchChannel;
        } else if (localFile != null) {
            title = plugin.getLocalMusic().titleFor(localFile, track.getInfo().title);
        } else if (discordFile != null) {
            title = usable(track.getInfo().title) ? track.getInfo().title : discordFile;
        } else {
            title = track.getInfo().title != null ? track.getInfo().title : "No name";
        }
        String authorN = Normalizer.normalize(author, Normalizer.Form.NFC);
        String titleN = Normalizer.normalize(title, Normalizer.Form.NFC);
        String videoId = track.getInfo().identifier;
        boolean isYoutube = trackLoader.isYoutubeIdentifier(resolved);

        if (!isYoutube || !trackLoader.hasCustomApi() || plugin.cdiscConfig().isYoutubeFastCreate()) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    stored(player, item, track, query, null, titleN, authorN, null));
            return;
        }

        CompletableFuture<AudioTrack> backendAhead = CompletableFuture.supplyAsync(
                () -> trackLoader.resolveViaBackend(resolved))
                .exceptionally(ignored -> null);

        trackLoader.probePlayability(track).whenComplete((playable, ex) -> {
            if (playable) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        stored(player, item, track, query, null, titleN, authorN, "lavaplayer"));
                return;
            }

            Bukkit.getLogger().warning("[CDisc] youtube-source couldn't decode '" + videoId + "'");

            AudioTrack backendTrack = backendAhead.join();
            if (backendTrack == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage("§c" + plugin.getMessageManager()
                                .get(player, "lavaplayer.track.notfound")));
                return;
            }
            String backendUrl = trackLoader.backendStreamUrlFor(videoId);
            Bukkit.getScheduler().runTask(plugin, () ->
                    stored(player, item, track, backendUrl, query, titleN, authorN, "backend"));
        });
    }

    private void stored(Player player, ItemStack item, AudioTrack track, String query, String fallback,
                        String title, String author, String fetch) {
        ItemUtils.saveTrackToDisc(item, query, fallback, title, author, fetch);
        long length = track.getInfo().length;
        if (!GoatHorns.isHorn(item)) {
            player.sendMessage("§a" + plugin.getMessageManager()
                    .get(player, "lavaplayer.track.loaded", title, author, TimeUtils.format(length)));
            return;
        }

        long clip = GoatHorns.record(plugin, item, length);
        if (plugin.getHornPlayer() != null) plugin.getHornPlayer().prefetch(item);
        player.sendMessage("§a" + plugin.getMessageManager()
                .get(player, "horn.recorded", title, author, TimeUtils.format(clip)));
        if (GoatHorns.trimmed(length, clip)) {
            player.sendMessage("§e" + plugin.getMessageManager()
                    .get(player, "horn.trimmed", TimeUtils.format(length), TimeUtils.format(clip)));
        }
    }

    public void startPlaying(Block block, String query) {
        startPlaying(block, query, null, null, null, null);
    }

    public void startPlaying(Block block, String query, String fallbackQuery, String discTitle, String discAuthor) {
        startPlaying(block, query, fallbackQuery, discTitle, discAuthor, null);
    }

    public void startPlaying(Block block, String query, String fallbackQuery, String discTitle, String discAuthor,
                             String musicFetch) {
        startPlaying(block, query, fallbackQuery, discTitle, discAuthor, musicFetch, 0L);
    }

    public void startPlaying(Block block, String query, String fallbackQuery, String discTitle, String discAuthor,
                             String musicFetch, long startAtMs) {
        String resolved = trackLoader.resolveQuery(query);
        if (resolved == null || voiceBackend == null) return;

        // Anything left here would keep playing unheard and make the next disc
        // look like it is already running.
        if (hasActiveSession(block)) stopPlaying(block, getGeneration(block));

        AudioPlayer player = trackLoader.createPlayer();

        SoundAnchor anchor = anchorManager.createFor(block);

        boolean synced = plugin.getSpeakerGroupManager().isMain(block);
        VoiceSession voiceSession = synced
                ? voiceBackend.createSyncedEntitySession(anchor.entity(), effectiveDistance(block))
                : voiceBackend.createEntitySession(anchor.entity(), effectiveDistance(block));

        if (voiceSession == null) {
            anchor.remove();
            return;
        }
        anchors.put(block, anchor);

        int gen = generation.getOrDefault(block, 0) + 1;

        AudioSession session = new AudioSession(player, voiceSession, discTitle, discAuthor);
        sessions.put(block, Collections.singletonList(session));
        generation.put(block, gen);

        attachSpeakers(block, session);
        applySpeakerSettings(block);

        BlockRef ref = new BlockRef(block);
        blockRefs.put(block, ref);

        AudioLoadResultHandler handler = new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) {

                if (startAtMs > 0 && track.isSeekable() && startAtMs < track.getDuration()) {
                    track.setPosition(startAtMs);
                }
                player.playTrack(track);
                player.setVolume(plugin.cdiscConfig().getVolume());
                CDiscMetrics.recordTrackPlayed(track.getSourceManager() != null
                        ? track.getSourceManager().getSourceName() : null);
                player.addListener(new AudioEventAdapter() {
                    @Override
                    public void onTrackEnd(AudioPlayer p, AudioTrack t, AudioTrackEndReason r) {
                        if (generation.getOrDefault(ref.get(), 0) != gen) return;

                        if (t.getInfo().isStream
                                && (r == AudioTrackEndReason.FINISHED || r == AudioTrackEndReason.LOAD_FAILED)
                                && session.allowStreamReconnect()) {
                            p.playTrack(t.makeClone());
                            return;
                        }

                        if (r == AudioTrackEndReason.LOAD_FAILED
                                && trackLoader.hasNextSource(t, resolved)) {

                            Bukkit.getLogger().warning("[CDisc] " + t.getUserData() + " could not play "
                                    + "this track; trying the next source...");
                            trackLoader.nextSourceAsync(t, resolved, replacement -> {
                                if (generation.getOrDefault(ref.get(), 0) != gen) return;
                                if (replacement == null) {
                                    Bukkit.getLogger().severe("[CDisc] Nothing else could serve it either.");
                                    Bukkit.getScheduler().runTask(plugin, () -> advanceOrStop(ref.get(), gen));
                                    return;
                                }
                                p.playTrack(replacement);
                            });
                            return;
                        }

                        if (r != AudioTrackEndReason.FINISHED) return;

                        if (getRepeatMode(ref.get()) == RepeatMode.TRACK) {
                            p.playTrack(t.makeClone());
                            return;
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> advanceOrStop(ref.get(), gen));
                    }

                    @Override
                    public void onTrackException(AudioPlayer p, AudioTrack t, com.sedmelluq.discord.lavaplayer.tools.FriendlyException e) {

                        boolean willRetry = trackLoader.hasNextSource(t, resolved);

                        String reason = e.getMessage() == null ? "" : e.getMessage().split("\n", 2)[0];
                        Bukkit.getLogger().warning("[CDisc] Playback failed for \""
                                + t.getInfo().title + "\" (" + t.getInfo().uri + ", via "
                                + t.getSourceManager().getSourceName() + "): " + reason);

                        if (TrackLoader.isSabrFailure(e)) {
                            Bukkit.getLogger().warning("[CDisc] YouTube answered with SABR "
                                    + "only: every format is there, none of them carries a "
                                    + "direct link, and it all goes through "
                                    + "serverAbrStreamingUrl, which youtube-source cannot play. "
                                    + "Neither the tokens nor this server's address are at fault.");
                        }

                        if (!willRetry) {
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            cause.printStackTrace();
                            warnOperators(t);
                        }
                    }

                    @Override
                    public void onTrackStuck(AudioPlayer p, AudioTrack t, long thresholdMs) {
                        Bukkit.getLogger().warning("[CDisc] Track stalled (no frames for over "
                                + thresholdMs + "ms): \"" + t.getInfo().title + "\" ("
                                + t.getInfo().uri + ", via "
                                + t.getSourceManager().getSourceName() + ")");
                    }
                });

                broadcaster.broadcast(ref.get(), audibleOrigin(ref.get()), track, discTitle, discAuthor,
                        (int) effectiveDistance(ref.get()), LavaPlayerManager.this::hasActiveSession);
            }

            @Override public void playlistLoaded(AudioPlaylist p) {
                if (!p.getTracks().isEmpty()) trackLoaded(p.getTracks().get(0));
            }

            @Override public void noMatches() {
                fallBack(ref, gen, fallbackQuery, discTitle, discAuthor);
            }

            @Override public void loadFailed(FriendlyException e) {
                e.printStackTrace();
                fallBack(ref, gen, fallbackQuery, discTitle, discAuthor);
            }
        };

        trackLoader.load(resolved, musicFetch, discTitle, discAuthor, handler);
    }

    // Load callbacks arrive on a LavaPlayer thread, and both halves spawn and remove
    // entities, which the server refuses off the main thread.
    private void fallBack(BlockRef ref, int gen, String fallbackQuery, String discTitle, String discAuthor) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            stopPlaying(ref.get(), gen);
            if (fallbackQuery != null) {
                startPlaying(ref.get(), fallbackQuery, null, discTitle, discAuthor, null);
            }
        });
    }

    public void setOnSessionEnded(Consumer<Block> callback) {
        this.onSessionEnded = callback;
    }

    public void stopPlaying(Block block, int gen) {
        if (generation.getOrDefault(block, 0) != gen) return;
        List<AudioSession> list = sessions.remove(block);
        if (list != null) list.forEach(AudioSession::stop);
        detachSpeakers(block);
        removeAnchor(block);
        generation.remove(block);
        blockRefs.remove(block);
        repeatModes.remove(block);
        shuffled.remove(block);
        beaconRangeLevel.remove(block);
        if (onSessionEnded != null) onSessionEnded.accept(block);
    }

    public boolean isPlaying(Block block) {
        List<AudioSession> list = sessions.get(block);
        return list != null && !list.isEmpty() && !list.get(0).getPlayer().isPaused();
    }

    public java.util.Set<Block> activeBlocks() {
        return new java.util.HashSet<>(sessions.keySet());
    }

    public boolean hasActiveSession(Block block) {
        List<AudioSession> list = sessions.get(block);
        return list != null && !list.isEmpty();
    }

    public void togglePause(Block block) {
        List<AudioSession> list = sessions.get(block);
        if (list == null || list.isEmpty()) return;
        AudioPlayer p = list.get(0).getPlayer();
        p.setPaused(!p.isPaused());
    }

    public void setPaused(Block block, boolean paused) {
        List<AudioSession> list = sessions.get(block);
        if (list == null || list.isEmpty()) return;
        list.get(0).getPlayer().setPaused(paused);
    }

    public boolean isPaused(Block block) {
        List<AudioSession> list = sessions.get(block);
        return list != null && !list.isEmpty() && list.get(0).getPlayer().isPaused();
    }

    public ItemStack nextDisc(Block block) {
        DiscQueue queue = queues.get(block);
        if (queue == null || queue.isEmpty()) return null;

        if (isShuffle(block)) return null;

        int current = queue.getCurrentIndex();
        if (getRepeatMode(block) == RepeatMode.TRACK) {
            return current >= 0 ? queue.getSlot(current) : null;
        }

        int next = queue.nextFilledAfter(current);
        if (next < 0 && getRepeatMode(block) == RepeatMode.QUEUE) {
            next = queue.firstFilled();
        }
        return next >= 0 ? queue.getSlot(next) : null;
    }

    public boolean isShuffle(Block block) {
        return shuffled.contains(block);
    }

    public boolean toggleShuffle(Block block) {
        if (shuffled.remove(block)) return false;
        shuffled.add(block);
        return true;
    }

    public void setShuffle(Block block, boolean on) {
        if (on) {
            shuffled.add(block);
        } else {
            shuffled.remove(block);
        }
    }

    public java.util.Set<Block> blocksWithPlaybackModes() {
        java.util.Set<Block> blocks = new java.util.LinkedHashSet<>(repeatModes.keySet());
        blocks.addAll(shuffled);
        return blocks;
    }

    private int randomOther(DiscQueue queue, int current) {
        java.util.List<Integer> choices = new java.util.ArrayList<>();
        for (int i = 0; i < DiscQueue.CAPACITY; i++) {
            if (i == current || queue.getSlot(i) == null) continue;
            choices.add(i);
        }
        if (choices.isEmpty()) return current >= 0 && queue.getSlot(current) != null ? current : -1;
        return choices.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(choices.size()));
    }

    public RepeatMode getRepeatMode(Block block) {
        return repeatModes.getOrDefault(block, RepeatMode.OFF);
    }

    public void setRepeatMode(Block block, RepeatMode mode) {
        if (mode == RepeatMode.OFF) {
            repeatModes.remove(block);
        } else {
            repeatModes.put(block, mode);
        }
    }

    public RepeatMode cycleRepeat(Block block) {
        RepeatMode next = getRepeatMode(block).next();
        if (next == RepeatMode.OFF) {
            repeatModes.remove(block);
        } else {
            repeatModes.put(block, next);
        }
        return next;
    }

    public DiscQueue getQueue(Block block) {
        DiscQueue live = queues.get(block);
        if (live != null) return live;

        DiscQueue restored = queueStore.take(block);
        if (restored == null) restored = queueStore.readBlock(block);
        if (restored == null) return null;
        queues.put(block, restored);
        return restored;
    }

    public DiscQueue getOrCreateQueue(Block block) {
        DiscQueue queue = getQueue(block);
        return queue != null ? queue : queues.computeIfAbsent(block, b -> new DiscQueue());
    }

    public List<ItemStack> drainQueue(Block block) {
        DiscQueue queue = getQueue(block);
        queues.remove(block);

        queueStore.forget(block);
        queueStore.clearBlock(block);
        savedRevisions.remove(block);
        return queue == null ? Collections.emptyList() : queue.drainAll();
    }

    public void handlePhysicalEject(Block block) {
        DiscQueue queue = getQueue(block);
        if (queue != null) {
            int current = queue.getCurrentIndex();
            if (current >= 0) queue.setSlot(current, null);
            queue.setCurrentIndex(-1);

            if (queue.isEmpty()) {
                queues.remove(block);
                queueStore.forget(block);
                queueStore.clearBlock(block);
                savedRevisions.remove(block);
            }

            refreshQueueGuis(block);
        }

        // Playback started from the GUI never starts the vanilla song, so no record-stop event follows.
        if (hasActiveSession(block)) stopPlaying(block, getGeneration(block));
    }

    public void releaseCurrentDisc(Block block, ItemStack disc) {
        DiscQueue queue = getQueue(block);
        if (queue != null) queue.setCurrentIndex(-1);

        if (block.getState() instanceof Jukebox jukebox && disc.isSimilar(jukebox.getRecord())) {
            plugin.getJukeboxListener().clearPhysicalRecord(block);
        }
    }

    public void seedQueue(Block block, ItemStack currentDisc) {
        seedQueue(block, currentDisc, false);
    }

    public void seedQueue(Block block, ItemStack currentDisc, boolean restoring) {
        DiscQueue queue = getOrCreateQueue(block);

        if (restoring) {
            int saved = queue.getCurrentIndex();
            if (saved >= 0 && currentDisc != null && currentDisc.isSimilar(queue.getSlot(saved))) {
                return;
            }
        }

        int slot = queue.firstEmpty();
        if (slot < 0) {

            plugin.getJukeboxListener().clearPhysicalRecord(block);
            dropDisc(block, currentDisc);
            return;
        }

        queue.setSlot(slot, currentDisc);
        queue.setCurrentIndex(slot);
    }

    private void advanceOrStop(Block block, int gen) {
        if (generation.getOrDefault(block, 0) != gen) return;

        DiscQueue queue = queues.get(block);
        RepeatMode mode = getRepeatMode(block);
        boolean currentEjected = false;

        if (queue != null && !queue.isEmpty()) {
            int cur = queue.getCurrentIndex();
            if (cur >= 0 && queue.getSlot(cur) != null) {
                switch (queue.getPolicy()) {
                    case EJECT -> {
                        ItemStack finished = queue.getSlot(cur);
                        queue.setSlot(cur, null);
                        dropDisc(block, finished);
                        currentEjected = true;
                    }
                    case MOVE_TO_END -> queue.moveToEnd(cur);
                    case NOTHING -> { }
                }
            }

            int next = isShuffle(block) ? randomOther(queue, cur) : queue.nextFilledAfter(cur);
            if (next < 0 && mode == RepeatMode.QUEUE) {
                next = queue.firstFilled();
            }
            if (next >= 0) {
                playQueueIndex(block, queue, next, gen);
                return;
            }
        }

        if (currentEjected) {
            plugin.getJukeboxListener().clearPhysicalRecord(block);
        }
        if (block.getState() instanceof Jukebox jukebox) {
            jukebox.stopPlaying();
        }
        stopPlaying(block, gen);
    }

    private void playQueueIndex(Block block, DiscQueue queue, int index, int gen) {
        queue.setCurrentIndex(index);
        ItemStack disc = queue.getSlot(index);
        ItemUtils.DiscData data = ItemUtils.readDiscData(disc);
        if (data == null) {

            queue.setSlot(index, null);
            dropDisc(block, disc);
            advanceOrStop(block, gen);
            return;
        }

        List<AudioSession> list = sessions.get(block);
        if (list == null || list.isEmpty()) {

            startPlaying(block, data.query(), data.fallback(), data.title(), data.author(), data.fetch());
            return;
        }
        AudioSession session = list.get(0);

        String title = data.title() != null ? Normalizer.normalize(data.title(), Normalizer.Form.NFC) : null;
        String author = data.author() != null ? Normalizer.normalize(data.author(), Normalizer.Form.NFC) : null;
        session.setDiscMeta(title, author);

        plugin.getJukeboxListener().swapDiscVisual(block, disc);

        loadInto(block, session.getPlayer(), data.query(), data.fallback(), title, author, data.fetch(), gen);
        refreshQueueGuis(block);
    }

    private void loadInto(Block block, AudioPlayer player, String query, String fallback,
                          String title, String author, String fetch, int gen) {

        BlockRef ref = blockRefs.get(block);
        String resolved = trackLoader.resolveQuery(query);
        if (resolved == null) {
            Bukkit.getScheduler().runTask(plugin, () -> advanceOrStop(block, gen));
            return;
        }
        trackLoader.load(resolved, fetch, title, author, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) {
                if (generation.getOrDefault(here(ref, block), 0) != gen) return;
                player.playTrack(track);
                player.setVolume(plugin.cdiscConfig().getVolume());
                broadcaster.broadcast(here(ref, block), audibleOrigin(here(ref, block)), track, title, author,
                        (int) effectiveDistance(here(ref, block)), LavaPlayerManager.this::hasActiveSession);
                Bukkit.getScheduler().runTask(plugin, () -> refreshQueueGuis(here(ref, block)));
            }

            @Override public void playlistLoaded(AudioPlaylist p) {
                if (!p.getTracks().isEmpty()) trackLoaded(p.getTracks().get(0));
            }

            @Override public void noMatches() {
                if (fallback != null) {
                    loadInto(here(ref, block), player, fallback, null, title, author, null, gen);
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> advanceOrStop(here(ref, block), gen));
                }
            }

            @Override public void loadFailed(FriendlyException e) {
                if (fallback != null) {
                    loadInto(here(ref, block), player, fallback, null, title, author, null, gen);
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> advanceOrStop(here(ref, block), gen));
                }
            }
        });
    }

    public boolean playInsertedDisc(Block block) {
        if (!(block.getState() instanceof org.bukkit.block.Jukebox jukebox)) return false;
        if (!jukebox.hasRecord()) return false;

        ItemStack record = jukebox.getRecord();
        ItemUtils.DiscData data = ItemUtils.readDiscData(record);
        if (data == null || data.query() == null) return false;

        seedQueue(block, record.clone());
        startPlaying(block, data.query(), data.fallback(),
                data.title(), data.author(), data.fetch());
        return true;
    }

    public void playQueueEntry(Block block, int index) {
        DiscQueue queue = queues.get(block);
        if (queue == null || queue.getSlot(index) == null) return;
        if (!hasActiveSession(block)) {
            startQueueEntry(block, queue, index);
            return;
        }
        playQueueIndex(block, queue, index, getGeneration(block));
    }

    private void startQueueEntry(Block block, DiscQueue queue, int index) {
        ItemStack disc = queue.getSlot(index);
        ItemUtils.DiscData data = ItemUtils.readDiscData(disc);
        if (data == null) return;

        queue.setCurrentIndex(index);

        plugin.getJukeboxListener().swapDiscVisual(block, disc);

        // Must stay in this tick: the record-start event the swap above emits would
        // otherwise find no session and seed a second copy of this disc into the queue.
        startPlaying(block, data.query(), data.fallback(), data.title(), data.author(), data.fetch());
    }

    public void skipToNext(Block block) {
        DiscQueue queue = queues.get(block);
        if (queue == null) return;
        int next = queue.nextFilledAfter(queue.getCurrentIndex());
        if (next < 0) next = queue.firstFilled();
        if (next >= 0) playQueueEntry(block, next);
    }

    public void skipToPrevious(Block block) {
        DiscQueue queue = queues.get(block);
        if (queue == null) return;
        int prev = queue.prevFilledBefore(queue.getCurrentIndex());
        if (prev < 0) prev = queue.lastFilled();
        if (prev >= 0) playQueueEntry(block, prev);
    }

    public void rebindAnchor(Block block, Location where) {
        if (where == null || where.getWorld() == null || voiceBackend == null) return;

        SoundAnchor anchor = anchors.get(block);
        List<AudioSession> playing = sessions.get(block);
        if (anchor == null || playing == null || playing.isEmpty()) return;
        if (anchor.inWorld(where.getWorld())) return;

        // The voice channel is tied to the anchor entity, and an entity cannot follow a
        // player into another world, so both are built again on the other side.
        anchorManager.moveTo(anchor, where);

        boolean synced = plugin.getSpeakerGroupManager().isMain(block);
        for (AudioSession session : playing) {
            VoiceSession fresh = synced
                    ? voiceBackend.createSyncedEntitySession(anchor.entity(), effectiveDistance(block))
                    : voiceBackend.createEntitySession(anchor.entity(), effectiveDistance(block));

            session.replaceVoiceSession(fresh);
        }
        applySpeakerSettings(block);
    }

    public void relocateSession(Block from, Block to) {
        if (from.equals(to)) return;

        moveEntry(sessions, from, to);
        moveEntry(generation, from, to);
        moveEntry(repeatModes, from, to);
        moveEntry(queues, from, to);
        moveEntry(beaconRangeLevel, from, to);
        moveEntry(anchors, from, to);

        if (shuffled.remove(from)) shuffled.add(to);

        savedRevisions.remove(from);
        queueStore.forget(from);
        queueStore.clearBlock(from);
        queueStore.forget(to);

        moveEntry(blockRefs, from, to);
        BlockRef ref = blockRefs.get(to);
        if (ref != null) ref.block = to;
    }

    private static <V> void moveEntry(Map<Block, V> map, Block from, Block to) {
        V value = map.remove(from);
        if (value != null) map.put(to, value);
    }

    public int queueSize(Block block) {
        DiscQueue queue = queues.get(block);
        return queue == null ? 0 : queue.filledCount();
    }

    private void dropDisc(Block block, ItemStack disc) {
        if (disc == null) return;
        org.bukkit.Location loc = block.getLocation().add(0.5, 1.1, 0.5);
        block.getWorld().dropItemNaturally(loc, disc);
    }

    private void refreshQueueGuis(Block block) {
        plugin.getQueueGuiManager().refreshOpen(block);
    }

    public float getBaseDistance() {
        return distance;
    }

    public int getBeaconRangeLevel(Block block) {
        return beaconRangeLevel.getOrDefault(block, 0);
    }

    public float effectiveDistance(Block block) {
        int boost = plugin.cdiscConfig().getBeaconRangeBoost(getBeaconRangeLevel(block));
        return distance + boost;
    }

    public void setBeaconRangeLevel(Block block, int level) {
        if (level <= 0) {
            beaconRangeLevel.remove(block);
        } else {
            beaconRangeLevel.put(block, level);
        }

        List<AudioSession> list = sessions.get(block);
        if (list == null || list.isEmpty()) return;
        float newDistance = effectiveDistance(block);
        for (AudioSession session : list) {

            for (VoiceSession output : session.allOutputs()) {
                output.setDistance(newDistance);
            }
        }
    }

    public void setPrivateListener(Block block, java.util.UUID listener) {
        List<AudioSession> list = sessions.get(block);
        if (list == null || list.isEmpty()) return;
        for (AudioSession session : list) {
            session.getVoiceSession().setPrivateListener(listener);
        }

        if (listener == null) {

            for (AudioSession session : list) {
                session.getVoiceSession().setDirectVolume(
                        dev.valkdz.cdisc.util.PlayerPrefs.VOLUME_FOLLOWS_JUKEBOX);
            }
            return;
        }

        Player carrier = Bukkit.getPlayer(listener);
        if (carrier == null) return;

        int volume = dev.valkdz.cdisc.util.PlayerPrefs.effectiveLocalVolume(
                carrier, SpeakerSettings.of(block).volume());
        for (AudioSession session : list) {
            session.getVoiceSession().setDirectVolume(volume);
        }
    }

    public void seek(Block block, long deltaMs) {
        AudioTrack track = getPlayingTrack(block);
        if (track == null || !track.isSeekable()) return;
        seekTo(block, track.getPosition() + deltaMs);
    }

    public void seekTo(Block block, long positionMs) {
        AudioTrack track = getPlayingTrack(block);
        if (track == null || !track.isSeekable()) return;
        long duration = track.getDuration();
        long clamped = Math.max(0, duration > 0 && duration != Long.MAX_VALUE ? Math.min(positionMs, duration) : positionMs);
        track.setPosition(clamped);
    }

    public long getPosition(Block block) {
        AudioTrack track = getPlayingTrack(block);
        return track != null ? track.getPosition() : -1;
    }

    public long getDuration(Block block) {
        AudioTrack track = getPlayingTrack(block);
        return track != null ? track.getDuration() : -1;
    }

    private AudioTrack getPlayingTrack(Block block) {
        List<AudioSession> list = sessions.get(block);
        if (list == null || list.isEmpty()) return null;
        return list.get(0).getPlayer().getPlayingTrack();
    }

    public record PlaybackInfo(String title, String author, long position, long duration,
                               boolean paused, RepeatMode repeatMode, boolean live) {
    }

    public boolean isLive(Block block) {
        AudioTrack track = getPlayingTrack(block);
        return track != null && track.getInfo().isStream;
    }

    public PlaybackInfo getPlaybackInfo(Block block) {
        List<AudioSession> list = sessions.get(block);
        if (list == null || list.isEmpty()) return null;

        AudioSession session = list.get(0);
        AudioPlayer player = session.getPlayer();
        AudioTrack track = player.getPlayingTrack();
        if (track == null) return null;

        String rawAuthor = session.getDiscAuthor() != null ? session.getDiscAuthor()
                : (track.getInfo().author != null ? track.getInfo().author : "Unknown");
        String rawTitle = session.getDiscTitle() != null ? session.getDiscTitle()
                : (track.getInfo().title != null ? track.getInfo().title : "No name");

        return new PlaybackInfo(
                Normalizer.normalize(rawTitle, Normalizer.Form.NFC),
                Normalizer.normalize(rawAuthor, Normalizer.Form.NFC),
                track.getPosition(),
                track.getDuration(),
                player.isPaused(),
                getRepeatMode(block),
                track.getInfo().isStream
        );
    }

    public int getGeneration(Block block) {
        return generation.getOrDefault(block, 0);
    }

    public List<AudioSession> getSessions(Block block) {
        return sessions.getOrDefault(block, Collections.emptyList());
    }

    public void shutdown() {

        if (queueSaveTask != null) {
            queueSaveTask.cancel();
            queueSaveTask = null;
        }
        saveQueues(false);

        sessions.values().forEach(list -> list.forEach(AudioSession::stop));
        sessions.clear();
        anchors.values().forEach(SoundAnchor::remove);
        anchors.clear();
        speakerOutputs.values().forEach(map ->
                map.values().forEach(output -> output.anchor().remove()));
        speakerOutputs.clear();
        generation.clear();
        trackLoader.shutdown();
    }

    public void reload() {
        trackLoader.reloadSources();
    }

    public YoutubeAudioSourceManager getYoutubeSourceManager() {
        return trackLoader.getYoutubeSourceManager();
    }
}
