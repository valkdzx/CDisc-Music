package dev.valkdz.cdisc.speaker;

import dev.valkdz.cdisc.Main;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.util.Locale;

public record SpeakerSettings(String tag, Channel channel, int volume, boolean particles) {

    public enum Channel {

        STEREO(true),

        MONO(false),

        LEFT(false),

        RIGHT(false);

        private final boolean stereo;

        Channel(boolean stereo) {
            this.stereo = stereo;
        }

        public boolean isStereo() {
            return stereo;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Channel next() {
            Channel[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public static Channel parse(String raw, Channel fallback) {
            if (raw == null) return fallback;
            try {
                return valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public static final int MUTED = 0;

    public static final int MIN_VOLUME = MUTED;
    public static final int MAX_VOLUME = 100;
    public static final int VOLUME_STEP = 10;

    public static final SpeakerSettings DEFAULT =
            new SpeakerSettings(null, Channel.STEREO, MAX_VOLUME, false);

    private static final NamespacedKey TAG_KEY =
            new NamespacedKey(Main.getInstance(), "cdisc_speaker_tag");
    private static final NamespacedKey CHANNEL_KEY =
            new NamespacedKey(Main.getInstance(), "cdisc_speaker_channel");
    private static final NamespacedKey VOLUME_KEY =
            new NamespacedKey(Main.getInstance(), "cdisc_speaker_volume");
    private static final NamespacedKey PARTICLES_KEY =
            new NamespacedKey(Main.getInstance(), "cdisc_speaker_particles");

    public static final int MAX_TAG_LENGTH = 24;

    public SpeakerSettings withTag(String tag) {
        return new SpeakerSettings(tag, channel, volume, particles);
    }

    public SpeakerSettings withChannel(Channel channel) {
        return new SpeakerSettings(tag, channel, volume, particles);
    }

    public SpeakerSettings withVolume(int volume) {
        return new SpeakerSettings(tag, channel, clampVolume(volume), particles);
    }

    public SpeakerSettings withParticles(boolean particles) {
        return new SpeakerSettings(tag, channel, volume, particles);
    }

    public SpeakerSettings louder() {
        return withVolume(clampVolume(volume + VOLUME_STEP));
    }

    public SpeakerSettings quieter() {
        return withVolume(clampVolume(volume - VOLUME_STEP));
    }

    public boolean isMuted() {
        return volume <= MUTED;
    }

    public static void flush(Block block) {
        SpeakerSettings remembered = lastKnown.get(block);
        if (remembered != null) store(block, remembered);
    }

    public static void forget(Block block) {
        lastKnown.remove(block);
    }

    public static int clampVolume(int volume) {
        return Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, volume));
    }

    public boolean isDefault() {
        return (tag == null || tag.isEmpty())
                && channel == Channel.STEREO
                && volume == MAX_VOLUME
                && !particles;
    }

    private static final Map<Block, SpeakerSettings> lastKnown = new ConcurrentHashMap<>();

    public static SpeakerSettings of(Block block) {
        if (!(block.getState() instanceof TileState state)) {
            SpeakerSettings remembered = lastKnown.get(block);
            return remembered != null ? remembered : DEFAULT;
        }
        PersistentDataContainer pdc = state.getPersistentDataContainer();

        String tag = pdc.get(TAG_KEY, PersistentDataType.STRING);
        Channel channel = Channel.parse(pdc.get(CHANNEL_KEY, PersistentDataType.STRING), Channel.STEREO);
        Integer volume = pdc.get(VOLUME_KEY, PersistentDataType.INTEGER);
        Byte particles = pdc.get(PARTICLES_KEY, PersistentDataType.BYTE);

        SpeakerSettings read = new SpeakerSettings(
                tag,
                channel,
                volume == null ? MAX_VOLUME : clampVolume(volume),
                particles != null && particles != 0
        );
        lastKnown.put(block, read);
        return read;
    }

    public static void store(Block block, SpeakerSettings settings) {

        lastKnown.put(block, settings);

        if (!(block.getState() instanceof TileState state)) return;
        PersistentDataContainer pdc = state.getPersistentDataContainer();

        if (settings.tag == null || settings.tag.isEmpty()) {
            pdc.remove(TAG_KEY);
        } else {
            pdc.set(TAG_KEY, PersistentDataType.STRING, settings.tag);
        }
        pdc.set(CHANNEL_KEY, PersistentDataType.STRING, settings.channel.name());
        pdc.set(VOLUME_KEY, PersistentDataType.INTEGER, settings.volume);
        pdc.set(PARTICLES_KEY, PersistentDataType.BYTE, (byte) (settings.particles ? 1 : 0));

        state.update(true, false);
    }
}
