package dev.valkdz.cdisc.voice.plasmovoice;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.feature.broadcast.MicrophoneAudio;
import dev.valkdz.cdisc.feature.broadcast.MicrophoneSink;
import su.plo.voice.api.audio.codec.AudioDecoder;
import su.plo.voice.api.event.EventPriority;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEndEvent;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEvent;
import su.plo.voice.proto.data.audio.capture.Activation;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlasmoMicrophoneTap {

    private static final int OUTPUT_RATE = MicrophoneAudio.OUTPUT_RATE;

    private final Main plugin;
    private final PlasmoVoiceServer voiceServer;
    private final MicrophoneSink sink;

    private record Decoder(AudioDecoder codec, boolean stereo) {
    }

    private final Map<UUID, Decoder> decoders = new ConcurrentHashMap<>();
    private volatile boolean warned;

    private PlasmoMicrophoneTap(Main plugin, PlasmoVoiceServer voiceServer, MicrophoneSink sink) {
        this.plugin = plugin;
        this.voiceServer = voiceServer;
        this.sink = sink;
    }

    public static boolean install(Main plugin, MicrophoneSink sink) {
        if (!(plugin.getPlasmoAddon() instanceof CDiscPlasmoAddon addon)) return false;
        PlasmoVoiceServer voiceServer = addon.getVoiceServer();
        if (voiceServer == null) return false;

        PlasmoMicrophoneTap tap = new PlasmoMicrophoneTap(plugin, voiceServer, sink);
        sink.onForget(tap::forget);
        voiceServer.getEventBus().register(addon, PlayerSpeakEvent.class, EventPriority.HIGHEST, tap::onSpeak);
        voiceServer.getEventBus().register(addon, PlayerSpeakEndEvent.class, EventPriority.NORMAL, tap::onSpeakEnd);
        return true;
    }

    // Plasmo calls this on its UDP thread, once per 20 ms packet of every speaking player.
    private void onSpeak(PlayerSpeakEvent event) {
        UUID id = event.getPlayer().getInstance().getUuid();
        PlayerAudioPacket packet = event.getPacket();
        int distance = packet.getDistance() > 0 ? packet.getDistance() : defaultDistance();
        if (event.isCancelled() || event.getPlayer().isMicrophoneMuted()) return;
        if (!sink.wants(id, distance)) {
            forget(id);
            return;
        }
        if (voiceServer.getMuteManager().getMute(id).isPresent()) return;

        if (!isProximity(packet.getActivationId())) return;

        try {
            byte[] opus = voiceServer.getDefaultEncryption().decrypt(packet.getData());
            short[] samples = decoderFor(id, packet.isStereo()).decode(opus);
            if (samples == null || samples.length == 0) return;
            sink.feed(id, MicrophoneAudio.toOutput(samples, packet.isStereo(), sampleRate()), distance);
        } catch (Exception e) {
            if (warned) return;
            warned = true;
            plugin.getLogger().warning("[CDisc] A microphone packet from Plasmo Voice could not be decoded: " + e);
        }
    }

    private void onSpeakEnd(PlayerSpeakEndEvent event) {
        UUID id = event.getPlayer().getInstance().getUuid();
        sink.ended(id);
    }


    private int defaultDistance() {
        return voiceServer.getActivationManager().getActivationByName("proximity")
                .map(Activation::getDefaultDistance)
                .filter(distance -> distance > 0)
                .orElse(16);
    }

    private void forget(UUID id) {
        Decoder decoder = decoders.remove(id);
        if (decoder != null) decoder.codec().close();
    }
    private boolean isProximity(UUID activation) {
        return voiceServer.getActivationManager().getActivationByName("proximity")
                .map(Activation::getId)
                .map(proximity -> Objects.equals(proximity, activation))
                .orElse(true);
    }

    private AudioDecoder decoderFor(UUID id, boolean stereo) throws Exception {
        Decoder current = decoders.get(id);
        if (current != null && current.stereo() == stereo) return current.codec();
        if (current != null) current.codec().close();

        AudioDecoder codec = voiceServer.createOpusDecoder(stereo);
        codec.open();
        decoders.put(id, new Decoder(codec, stereo));
        return codec;
    }

    private int sampleRate() {
        try {
            int rate = voiceServer.getConfig().voice().sampleRate();
            return rate > 0 ? rate : OUTPUT_RATE;
        } catch (Exception e) {
            return OUTPUT_RATE;
        }
    }
}
