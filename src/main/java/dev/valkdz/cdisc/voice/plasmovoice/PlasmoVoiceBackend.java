package dev.valkdz.cdisc.voice.plasmovoice;

import dev.valkdz.cdisc.voice.VoiceBackend;
import dev.valkdz.cdisc.voice.VoiceSession;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.source.AudioSender;
import su.plo.voice.api.server.audio.source.ServerDirectSource;
import su.plo.voice.api.server.audio.source.ServerEntitySource;
import su.plo.voice.api.server.audio.source.ServerProximitySource;
import su.plo.voice.api.server.audio.source.ServerStaticSource;
import dev.valkdz.cdisc.speaker.SpeakerSettings;
import su.plo.voice.api.server.player.VoicePlayer;
import su.plo.slib.api.server.entity.McServerEntity;
import su.plo.slib.api.server.position.ServerPos3d;
import su.plo.slib.api.server.world.McServerWorld;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class PlasmoVoiceBackend implements VoiceBackend {

    private final CDiscPlasmoAddon addon;

    public PlasmoVoiceBackend(CDiscPlasmoAddon addon) {
        this.addon = addon;
    }

    @Override
    public String name() {
        return "Plasmo Voice";
    }

    @Override
    public boolean wantsPcm() {
        return true;
    }

    @Override
    public boolean serves(UUID player) {
        try {
            PlasmoVoiceServer voiceServer = addon.getVoiceServer();
            if (voiceServer == null) return false;
            return voiceServer.getPlayerManager()
                    .getPlayerById(player)
                    .filter(VoicePlayer::hasVoiceChat)
                    .filter(voicePlayer -> !voicePlayer.isVoiceDisabled())
                    .isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public VoiceSession createSession(Block block, World world, float distance) {
        PlasmoVoiceServer voiceServer = addon.getVoiceServer();
        ServerSourceLine sourceLine = addon.getSourceLine();
        if (voiceServer == null || sourceLine == null) return null;

        Optional<McServerWorld> mcWorld = voiceServer.getMinecraftServer()
                .getWorlds()
                .stream()
                .filter(w -> w.getName().equals(world.getName()))
                .findAny();

        if (mcWorld.isEmpty()) return null;

        ServerPos3d position = new ServerPos3d(
                mcWorld.get(),
                block.getX() + 0.5D,
                block.getY() + 0.5D,
                block.getZ() + 0.5D
        );

        ServerStaticSource source = sourceLine.createStaticSource(position, true);
        if (source == null) return null;

        return startSender(voiceServer, sourceLine, source, distance, false);
    }

    @Override
    public VoiceSession createEntitySession(Entity anchor, float distance) {
        return entitySession(anchor, distance, false);
    }

    @Override
    public VoiceSession createSyncedEntitySession(Entity anchor, float distance) {
        return entitySession(anchor, distance, true);
    }

    private VoiceSession entitySession(Entity anchor, float distance, boolean synced) {
        PlasmoVoiceServer voiceServer = addon.getVoiceServer();
        ServerSourceLine sourceLine = addon.getSourceLine();
        if (voiceServer == null || sourceLine == null) return null;

        McServerEntity entity = voiceServer.getMinecraftServer().getEntityByInstance(anchor);
        if (entity == null) return null;

        ServerEntitySource source = sourceLine.createEntitySource(entity, true);
        if (source == null) return null;

        return startSender(voiceServer, sourceLine, source, distance, synced);
    }

    private VoiceSession startSender(PlasmoVoiceServer voiceServer,
                                     ServerSourceLine sourceLine,
                                     ServerProximitySource<?> source,
                                     float distance,
                                     boolean synced) {
        Session session = new Session(voiceServer, sourceLine, source, distance, synced);
        session.startProximity();
        return session;
    }

    private static class Session implements VoiceSession {

        private static final long HANDOFF_OVERLAP_TICKS = 2L;

        private static final int HANDOFF_SETTLE_TICKS = 2;

        private static final int HANDOFF_TIMEOUT_TICKS = 40;

        private static final long SEQUENCE_HANDOVER_GAP = 50L;

        private final PlasmoVoiceServer voiceServer;
        private final ServerSourceLine sourceLine;

        private final ServerProximitySource<?> proximitySource;
        private PcmQueueFrameProvider proximityProvider;
        private AudioSender proximitySender;

        private volatile ServerDirectSource directSource;
        private volatile PcmQueueFrameProvider directProvider;
        private volatile AudioSender directSender;
        private volatile UUID directListener;

        private volatile UUID filteredCarrier;

        private volatile Set<UUID> excluded = Set.of();

        private volatile int directVolume = -1;

        private volatile PcmQueueFrameProvider lingeringProvider;

        private volatile short distance;
        private volatile boolean closed = false;

        private volatile boolean pushed;

        private volatile long sequence = 0L;

        private volatile long senderStartedAt = 0L;

        Session(PlasmoVoiceServer voiceServer,
                ServerSourceLine sourceLine,
                ServerProximitySource<?> proximitySource,
                float distance,
                boolean synced) {
            this.voiceServer = voiceServer;
            this.sourceLine = sourceLine;
            this.proximitySource = proximitySource;
            this.distance = clampDistance(distance);
            this.pushed = synced;
        }

        void startProximity() {

            proximityProvider = new PcmQueueFrameProvider(
                    voiceServer, PcmQueueFrameProvider.SOLO_CAPACITY, true);
            if (pushed) return;

            proximitySender = proximitySource.createAudioSender(proximityProvider, this::currentDistance);
            senderStartedAt = System.nanoTime();
            proximitySender.start();
        }

        Short currentDistance() {
            return distance;
        }

        private static short clampDistance(float distance) {
            return (short) Math.max(0, Math.min(distance, Short.MAX_VALUE));
        }

        @Override
        public void sendFrame(byte[] opus) {
            if (closed) return;

            if (pushed) {

                byte[] ready = proximityProvider.prepare(opus);
                if (ready != null) {
                    try {
                        proximitySource.sendAudioFrame(ready, sequence++, distance);
                    } catch (Exception ignored) {

                    }
                }
            } else {
                proximityProvider.offer(opus);
            }

            PcmQueueFrameProvider direct = directProvider;
            if (direct != null) direct.offer(opus);

            PcmQueueFrameProvider lingering = lingeringProvider;
            if (lingering != null) lingering.offer(opus);
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            stopDirect();

            PcmQueueFrameProvider lingering = lingeringProvider;
            lingeringProvider = null;
            if (lingering != null) lingering.close();

            AudioSender sender = proximitySender;
            proximitySender = null;
            if (sender != null) {
                try {
                    sender.stop();
                } catch (Exception ignored) {
                }
            } else {

                try {
                    proximitySource.sendAudioEnd(sequence, distance);
                } catch (Exception ignored) {
                }
            }
            proximityProvider.close();
            try {
                proximitySource.remove();
            } catch (Exception ignored) {
            }
        }

        @Override
        public void setDistance(float distance) {
            this.distance = clampDistance(distance);
        }

        @Override
        public void setDirectVolume(int volume) {
            this.directVolume = volume;

            PcmQueueFrameProvider direct = directProvider;
            if (direct != null && volume >= 0) direct.setVolume(volume);
        }

        @Override
        public void applySpeakerSettings(SpeakerSettings settings) {
            if (closed || settings == null) return;
            boolean stereo = settings.channel().isStereo();

            PcmQueueFrameProvider provider = proximityProvider;
            if (provider != null) {
                provider.setChannel(settings.channel());
                provider.setVolume(settings.volume());
            }

            PcmQueueFrameProvider direct = directProvider;
            if (direct != null) {
                direct.setChannel(settings.channel());

                direct.setVolume(directVolume < 0 ? settings.volume() : directVolume);
            }

            try {
                proximitySource.setStereo(stereo);
                proximitySource.setDirty();
                ServerDirectSource directSource = this.directSource;
                if (directSource != null) {
                    directSource.setStereo(stereo);
                    directSource.setDirty();
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void setSynced(boolean synced) {
            if (!synced || pushed || closed) return;

            PcmQueueFrameProvider provider = proximityProvider;
            if (provider == null) return;

            AudioSender sender = proximitySender;
            proximitySender = null;
            if (sender != null) {
                try {
                    sender.stop();
                } catch (Exception ignored) {
                }
            }

            long ticks = (System.nanoTime() - senderStartedAt) / 20_000_000L;
            sequence = Math.max(provider.framesSent(), ticks) + SEQUENCE_HANDOVER_GAP;
            pushed = true;
        }

        @Override
        public void setPrivateListener(UUID listener) {
            if (closed) return;
            if (Objects.equals(listener, directListener)) return;

            if (listener == null) {

                filteredCarrier = null;
                applyFilters();
                stopDirectAfter(HANDOFF_OVERLAP_TICKS);
                return;
            }

            stopDirect();

            if (excluded.contains(listener)) return;

            Optional<? extends VoicePlayer> voicePlayer =
                    voiceServer.getPlayerManager().getPlayerById(listener);
            if (voicePlayer.isEmpty()) {
                warn("no Plasmo Voice player for " + listener
                        + "; carried jukebox stays on the positional source");
                return;
            }

            ServerDirectSource direct = sourceLine.createDirectSource(voicePlayer.get(), true);
            if (direct == null) {
                warn("Plasmo Voice returned no direct source; "
                        + "carried jukebox stays on the positional source");
                return;
            }

            PcmQueueFrameProvider provider = new PcmQueueFrameProvider(
                    voiceServer, PcmQueueFrameProvider.SOLO_CAPACITY, true);

            directListener = listener;
            directSource = direct;
            directProvider = provider;
            directSender = direct.createAudioSender(provider);
            directSender.start();

            scheduleHandoff(listener, provider);
        }

        private void scheduleHandoff(UUID listener, PcmQueueFrameProvider provider) {
            new BukkitRunnable() {
                private int waited = 0;
                private int settling = 0;

                @Override
                public void run() {

                    if (closed || !Objects.equals(listener, directListener)) {
                        cancel();
                        return;
                    }

                    boolean live = provider.hasDelivered();
                    if (!live && waited < HANDOFF_TIMEOUT_TICKS) {
                        waited++;
                        return;
                    }

                    if (live && settling < HANDOFF_SETTLE_TICKS) {
                        settling++;
                        return;
                    }

                    filteredCarrier = listener;
                    applyFilters();
                    cancel();

                    if (!live) {
                        warn("direct feed for " + listener + " never started; "
                                + "handing over anyway, audio may drop out");
                    }
                }
            }.runTaskTimer(dev.valkdz.cdisc.Main.getInstance(), 1L, 1L);
        }

        private void stopDirectAfter(long ticks) {
            AudioSender sender = directSender;
            PcmQueueFrameProvider provider = directProvider;
            ServerDirectSource source = directSource;

            directSender = null;
            directProvider = null;
            directSource = null;
            directListener = null;
            lingeringProvider = provider;

            if (sender == null && provider == null && source == null) return;

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (lingeringProvider == provider) lingeringProvider = null;
                    if (sender != null) {
                        try {
                            sender.stop();
                        } catch (Exception ignored) {
                        }
                    }
                    if (provider != null) provider.close();
                    if (source != null) {
                        try {
                            source.remove();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }.runTaskLater(dev.valkdz.cdisc.Main.getInstance(), ticks);
        }

        private static UUID uuidOf(VoicePlayer player) {
            try {
                return player.getInstance().getUuid();
            } catch (Exception e) {
                return null;
            }
        }

        private void stopDirect() {
            AudioSender sender = directSender;
            PcmQueueFrameProvider provider = directProvider;
            ServerDirectSource source = directSource;

            directSender = null;
            directProvider = null;
            directSource = null;
            directListener = null;

            if (filteredCarrier != null) {
                filteredCarrier = null;
                applyFilters();
            }

            if (sender != null) {
                try {
                    sender.stop();
                } catch (Exception ignored) {
                }
            }
            if (provider != null) provider.close();
            if (source != null) {
                try {
                    source.remove();
                } catch (Exception ignored) {
                }
            }
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

            try {
                proximitySource.clearFilters();
                if (carrier == null && skip.isEmpty()) return;

                proximitySource.<VoicePlayer>addFilter(player -> {
                    UUID id = uuidOf(player);
                    if (id == null) return true;
                    return !id.equals(carrier) && !skip.contains(id);
                });
            } catch (Exception e) {
                warn("failed to set source filters: " + e);
            }
        }

        private static void warn(String message) {
            dev.valkdz.cdisc.Main.getInstance().getLogger().warning(message);
        }
    }
}
