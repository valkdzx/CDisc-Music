package dev.valkdz.cdisc.voice.plasmovoice;

import dev.valkdz.cdisc.speaker.SpeakerSettings;
import su.plo.voice.api.audio.codec.AudioEncoder;
import su.plo.voice.api.encryption.Encryption;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.provider.AudioFrameProvider;
import su.plo.voice.api.server.audio.provider.AudioFrameResult;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class PcmQueueFrameProvider implements AudioFrameProvider {

    public static final int SOLO_CAPACITY = 50;

    private final Encryption encryption;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(SOLO_CAPACITY);

    private volatile AudioEncoder encoder;

    private volatile SpeakerSettings.Channel channel = SpeakerSettings.Channel.STEREO;

    private volatile int volume = SpeakerSettings.MAX_VOLUME;

    private final Object encoderLock = new Object();

    private final PlasmoVoiceServer voiceServer;

    private final int maxDepth;

    private volatile boolean closed = false;
    private volatile boolean loggedError = false;
    private volatile boolean delivered = false;

    private volatile long sent = 0L;

    public PcmQueueFrameProvider(PlasmoVoiceServer voiceServer) {
        this(voiceServer, SOLO_CAPACITY);
    }

    public PcmQueueFrameProvider(PlasmoVoiceServer voiceServer, int depth) {
        this.voiceServer = voiceServer;
        this.encryption = voiceServer.getDefaultEncryption();
        this.maxDepth = clampDepth(depth);
        this.encoder = null;
    }

    public PcmQueueFrameProvider(PlasmoVoiceServer voiceServer, int depth, boolean stereo) {
        this.voiceServer = voiceServer;
        this.encryption = voiceServer.getDefaultEncryption();
        this.maxDepth = clampDepth(depth);
        this.channel = stereo ? SpeakerSettings.Channel.STEREO : SpeakerSettings.Channel.MONO;
        this.encoder = openEncoder(voiceServer, stereo);
    }

    private static AudioEncoder openEncoder(PlasmoVoiceServer voiceServer, boolean stereo) {
        try {
            AudioEncoder created = voiceServer.createOpusEncoder(stereo);
            created.open();
            return created;
        } catch (Exception e) {

            dev.valkdz.cdisc.Main.getInstance().getLogger().severe(
                    "[CDisc] Could not create an Opus encoder for a jukebox source: " + e);
            return null;
        }
    }

    public void setChannel(SpeakerSettings.Channel channel) {
        if (closed || channel == null) return;
        synchronized (encoderLock) {
            if (encoder == null) return;
            if (this.channel == channel) return;

            if (this.channel.isStereo() != channel.isStereo()) {
                AudioEncoder replacement = openEncoder(voiceServer, channel.isStereo());
                if (replacement == null) return;

                AudioEncoder previous = encoder;
                encoder = replacement;
                try {
                    previous.close();
                } catch (Exception ignored) {
                }
            }
            this.channel = channel;
        }
    }

    public void setVolume(int volume) {
        this.volume = SpeakerSettings.clampVolume(volume);
    }

    private static int clampDepth(int depth) {
        return Math.max(1, Math.min(depth, SOLO_CAPACITY));
    }

    public void offer(byte[] pcmLittleEndian) {
        if (closed) return;

        while (queue.size() >= maxDepth) {
            if (queue.poll() == null) break;
        }
        queue.offer(pcmLittleEndian);
    }

    @Override
    public AudioFrameResult provide20ms() {
        if (closed) {
            return AudioFrameResult.Finished.INSTANCE;
        }

        byte[] frame = queue.poll();
        if (frame == null) {

            return new AudioFrameResult.Provided(null);
        }

        return new AudioFrameResult.Provided(prepare(frame));
    }

    public byte[] prepare(byte[] pcmLittleEndian) {
        if (closed || pcmLittleEndian == null) return null;

        if (volume <= SpeakerSettings.MUTED) return null;

        try {
            byte[] opus;

            synchronized (encoderLock) {
                opus = encoder == null ? pcmLittleEndian
                        : encoder.encode(dev.valkdz.cdisc.voice.PcmShaper.toSamples(
                                pcmLittleEndian, channel, volume));
            }
            byte[] encrypted = encryption.encrypt(opus);
            delivered = true;
            sent++;
            return encrypted;
        } catch (Exception e) {
            if (!loggedError) {
                loggedError = true;
                dev.valkdz.cdisc.Main.getInstance().getLogger().severe(
                        "[CDisc] Plasmo Voice failed to prepare an audio frame "
                                + "(bytes=" + pcmLittleEndian.length + "): " + e);
            }
            return null;
        }
    }

    public long framesSent() {
        return sent;
    }

    public boolean hasDelivered() {
        return delivered;
    }

    public void close() {
        if (closed) return;
        closed = true;
        queue.clear();

        synchronized (encoderLock) {
            if (encoder != null) {
                try {
                    encoder.close();
                } catch (Exception ignored) {
                }
                encoder = null;
            }
        }
    }
}
