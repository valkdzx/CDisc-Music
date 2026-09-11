package dev.valkdz.cdisc.voice.simplevoicechat;

import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import dev.valkdz.cdisc.speaker.SpeakerSettings;
import dev.valkdz.cdisc.voice.PcmShaper;
import dev.valkdz.cdisc.voice.VoiceBackend;
import dev.valkdz.cdisc.voice.VoiceSession;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public class SimpleVoiceChatBackend implements VoiceBackend {

    private final VoicechatServerApi api;
    private final String categoryId;

    public SimpleVoiceChatBackend(VoicechatServerApi api, String categoryId) {
        this.api = api;
        this.categoryId = categoryId;
    }

    @Override
    public String name() {
        return "Simple Voice Chat";
    }

    @Override
    public boolean wantsPcm() {
        return true;
    }

    @Override
    public boolean serves(UUID player) {
        try {
            VoicechatConnection connection = api.getConnectionOf(player);
            return connection != null
                    && connection.isInstalled()
                    && connection.isConnected()
                    && !connection.isDisabled();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public VoiceSession createSession(Block block, World world, float distance) {
        LocationalAudioChannel channel = api.createLocationalAudioChannel(
                UUID.randomUUID(),
                api.fromServerLevel(world),
                api.createPosition(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5)
        );

        if (channel == null) return null;

        if (categoryId != null && !categoryId.isEmpty()) {
            channel.setCategory(categoryId);
        }
        channel.setDistance(distance);

        return new LocationalSession(api, categoryId, channel);
    }

    @Override
    public VoiceSession createEntitySession(Entity anchor, float distance) {
        EntityAudioChannel channel = api.createEntityAudioChannel(
                UUID.randomUUID(),
                api.fromEntity(anchor)
        );

        if (channel == null) return null;

        if (categoryId != null && !categoryId.isEmpty()) {
            channel.setCategory(categoryId);
        }
        channel.setDistance(distance);

        return new EntitySession(api, categoryId, channel);
    }

    private abstract static class ChannelSession implements VoiceSession {

        private static final long HANDOFF_OVERLAP_TICKS = 2L;

        private static final int HANDOFF_FRAMES = 3;

        private static final int HANDOFF_TIMEOUT_TICKS = 40;

        private static final Predicate<ServerPlayer> NO_FILTER = player -> true;

        protected final VoicechatServerApi api;
        protected final String categoryId;

        private final AudioChannel channel;

        private volatile StaticAudioChannel privateChannel;
        private volatile UUID privateListener;
        private volatile int privateFrames;

        private volatile UUID filteredCarrier;

        private volatile Set<UUID> excluded = Set.of();

        private volatile StaticAudioChannel lingeringChannel;

        private volatile BukkitTask handoff;
        private volatile boolean closed;

        // One encoder per output: two speakers fed from the same decode must be able
        // to carry different audio.
        private final Object encoderLock = new Object();
        private OpusEncoder encoder;

        private OpusEncoder directEncoder;

        private volatile int directVolume = -1;
        private boolean loggedEncodeError;

        private volatile SpeakerSettings settings = SpeakerSettings.DEFAULT;

        ChannelSession(VoicechatServerApi api, String categoryId, AudioChannel channel) {
            this.api = api;
            this.categoryId = categoryId;
            this.channel = channel;
            this.encoder = openEncoder(api);
        }

        private static OpusEncoder openEncoder(VoicechatServerApi api) {
            try {
                return api.createEncoder();
            } catch (Exception e) {

                dev.valkdz.cdisc.Main.getInstance().getLogger().severe(
                        "[CDisc] Could not create an Opus encoder for a jukebox channel: " + e);
                return null;
            }
        }

        @Override
        public void sendFrame(byte[] pcm) {
            if (closed) return;

            SpeakerSettings current = settings;

            if (current.isMuted()) return;

            byte[] opus = encode(pcm, current);
            if (opus == null) return;

            channel.send(opus);

            int own = directVolume;
            byte[] carriers = own < 0 || own == current.volume()
                    ? opus
                    : encodeDirect(pcm, current, own);
            if (carriers == null) carriers = opus;

            StaticAudioChannel priv = privateChannel;
            if (priv != null && !priv.isClosed()) {
                priv.send(carriers);
                privateFrames++;
            }

            StaticAudioChannel lingering = lingeringChannel;
            if (lingering != null && !lingering.isClosed()) {
                lingering.send(carriers);
            }
        }

        private byte[] encodeDirect(byte[] pcm, SpeakerSettings current, int volume) {
            SpeakerSettings.Channel wanted = current.channel() == SpeakerSettings.Channel.STEREO
                    ? SpeakerSettings.Channel.MONO
                    : current.channel();

            short[] samples = PcmShaper.toSamples(pcm, wanted, volume);

            synchronized (encoderLock) {
                if (directEncoder == null || directEncoder.isClosed()) {
                    directEncoder = openEncoder(api);
                }
                if (directEncoder == null) return null;
                try {
                    return directEncoder.encode(samples);
                } catch (Exception e) {
                    return null;
                }
            }
        }

        private byte[] encode(byte[] pcm, SpeakerSettings current) {
            SpeakerSettings.Channel wanted = current.channel() == SpeakerSettings.Channel.STEREO
                    ? SpeakerSettings.Channel.MONO
                    : current.channel();

            short[] samples = PcmShaper.toSamples(pcm, wanted, current.volume());

            synchronized (encoderLock) {
                if (encoder == null || encoder.isClosed()) return null;
                try {
                    return encoder.encode(samples);
                } catch (Exception e) {
                    if (!loggedEncodeError) {
                        loggedEncodeError = true;
                        dev.valkdz.cdisc.Main.getInstance().getLogger().severe(
                                "[CDisc] Simple Voice Chat failed to encode a jukebox frame "
                                        + "(bytes=" + pcm.length + "): " + e);
                    }
                    return null;
                }
            }
        }

        @Override
        public void applySpeakerSettings(SpeakerSettings settings) {
            if (settings != null) this.settings = settings;
        }

        @Override
        public boolean isClosed() {
            return channel.isClosed();
        }

        @Override
        public void close() {
            closed = true;
            cancelHandoff();

            releaseTargets(privateChannel);
            releaseTargets(lingeringChannel);
            privateChannel = null;
            lingeringChannel = null;
            privateListener = null;

            synchronized (encoderLock) {
                OpusEncoder open = encoder;
                encoder = null;
                if (open != null && !open.isClosed()) {
                    try {
                        open.close();
                    } catch (Exception ignored) {
                    }
                }

                OpusEncoder direct = directEncoder;
                directEncoder = null;
                if (direct != null && !direct.isClosed()) {
                    try {
                        direct.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        @Override
        public void setDirectVolume(int volume) {
            this.directVolume = volume;
        }

        @Override
        public void setPrivateListener(UUID listener) {
            if (closed) return;
            if (Objects.equals(listener, privateListener)) return;

            if (listener == null) {

                filteredCarrier = null;
                applyFilters();
                stopPrivateAfter(HANDOFF_OVERLAP_TICKS);
                return;
            }

            stopPrivate();

            if (excluded.contains(listener)) return;

            VoicechatConnection connection = connectionOf(listener);
            if (connection == null || !connection.isConnected()) {
                warn("no Simple Voice Chat connection for " + listener
                        + "; carried jukebox stays on the positional channel");
                return;
            }

            StaticAudioChannel priv = openStaticChannel(connection);
            if (priv == null) {
                warn("Simple Voice Chat returned no static channel; "
                        + "carried jukebox stays on the positional channel");
                return;
            }

            privateListener = listener;
            privateChannel = priv;
            privateFrames = 0;

            scheduleHandoff(listener);
        }

        private StaticAudioChannel openStaticChannel(VoicechatConnection connection) {
            StaticAudioChannel priv = null;
            try {
                priv = api.createStaticAudioChannel(UUID.randomUUID());
                if (priv != null) priv.addTarget(connection);
            } catch (Exception e) {
                priv = null;
            }

            if (priv == null) {
                ServerPlayer player = connection.getPlayer();
                if (player == null) return null;
                priv = api.createStaticAudioChannel(
                        UUID.randomUUID(), player.getServerLevel(), connection);
            }

            if (priv == null) return null;

            if (categoryId != null && !categoryId.isEmpty()) {
                priv.setCategory(categoryId);
            }

            priv.setBypassGroupIsolation(true);
            return priv;
        }

        private void scheduleHandoff(UUID listener) {
            cancelHandoff();
            handoff = new BukkitRunnable() {
                private int waited = 0;

                @Override
                public void run() {

                    if (closed || !Objects.equals(listener, privateListener)) {
                        cancel();
                        return;
                    }

                    boolean live = privateFrames >= HANDOFF_FRAMES;
                    if (!live && waited < HANDOFF_TIMEOUT_TICKS) {
                        waited++;
                        return;
                    }

                    filteredCarrier = listener;
                    applyFilters();
                    cancel();

                    if (!live) {
                        warn("static feed for " + listener + " never started; "
                                + "handing over anyway, audio may drop out");
                    }
                }
            }.runTaskTimer(dev.valkdz.cdisc.Main.getInstance(), 1L, 1L);
        }

        private void stopPrivateAfter(long ticks) {
            cancelHandoff();

            StaticAudioChannel outgoing = privateChannel;

            privateChannel = null;
            privateListener = null;
            privateFrames = 0;
            releaseTargets(lingeringChannel);
            lingeringChannel = outgoing;

            if (outgoing == null) return;

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (lingeringChannel == outgoing) {
                        lingeringChannel = null;
                    }
                    releaseTargets(outgoing);
                }
            }.runTaskLater(dev.valkdz.cdisc.Main.getInstance(), ticks);
        }

        private void stopPrivate() {
            cancelHandoff();
            releaseTargets(privateChannel);
            privateChannel = null;
            privateListener = null;
            privateFrames = 0;

            if (filteredCarrier != null) {
                filteredCarrier = null;
                applyFilters();
            }
        }

        private void cancelHandoff() {
            BukkitTask task = handoff;
            handoff = null;
            if (task != null) task.cancel();
        }

        @Override
        public void setExcludedListeners(Set<UUID> excluded) {
            this.excluded = excluded == null || excluded.isEmpty()
                    ? Set.of()
                    : Set.copyOf(excluded);
            applyFilters();
        }

        private void applyFilters() {
            UUID carrier = filteredCarrier;
            Set<UUID> skip = excluded;

            Predicate<ServerPlayer> filter = carrier == null && skip.isEmpty()
                    ? NO_FILTER
                    : player -> {
                        UUID id = uuidOf(player);
                        if (id == null) return true;
                        return !id.equals(carrier) && !skip.contains(id);
                    };

            try {
                channel.setFilter(filter);
            } catch (Exception e) {
                warn("failed to set channel filter: " + e);
            }
        }

        private static void releaseTargets(StaticAudioChannel channel) {
            if (channel == null) return;
            try {
                channel.clearTargets();
            } catch (Exception e) {
                warn("failed to clear static channel targets: " + e);
            }
        }

        private VoicechatConnection connectionOf(UUID listener) {
            try {
                return api.getConnectionOf(listener);
            } catch (Exception e) {
                return null;
            }
        }

        private static UUID uuidOf(ServerPlayer player) {
            try {
                return player.getUuid();
            } catch (Exception e) {
                return null;
            }
        }

        private static void warn(String message) {
            dev.valkdz.cdisc.Main.getInstance().getLogger().warning(message);
        }
    }

    private static final class LocationalSession extends ChannelSession {
        private final LocationalAudioChannel channel;

        LocationalSession(VoicechatServerApi api, String categoryId, LocationalAudioChannel channel) {
            super(api, categoryId, channel);
            this.channel = channel;
        }

        @Override
        public void setDistance(float distance) {
            channel.setDistance(distance);
        }
    }

    private static final class EntitySession extends ChannelSession {
        private final EntityAudioChannel channel;

        EntitySession(VoicechatServerApi api, String categoryId, EntityAudioChannel channel) {
            super(api, categoryId, channel);
            this.channel = channel;
        }

        @Override
        public void setDistance(float distance) {
            channel.setDistance(distance);
        }
    }
}
