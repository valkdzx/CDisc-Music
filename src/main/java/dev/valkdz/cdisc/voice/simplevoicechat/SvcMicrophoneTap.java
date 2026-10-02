package dev.valkdz.cdisc.voice.simplevoicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.feature.broadcast.MicrophoneAudio;
import dev.valkdz.cdisc.feature.broadcast.MicrophoneSink;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SvcMicrophoneTap {

    private final Main plugin;
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private volatile boolean warned;
    private volatile boolean registered;

    public SvcMicrophoneTap(Main plugin) {
        this.plugin = plugin;
    }

    // Simple Voice Chat calls this on its network thread for every 20 ms microphone packet.
    public void onMicrophone(MicrophonePacketEvent event) {
        MicrophoneSink sink = plugin.getBroadcastManager();
        VoicechatConnection sender = event.getSenderConnection();
        if (sink == null || sender == null || event.isCancelled() || sender.isDisabled()) return;
        if (!registered) {
            registered = true;
            sink.onForget(this::forget);
        }

        Group group = sender.getGroup();
        if (group != null && group.getType() != Group.Type.OPEN) return;

        UUID id = sender.getPlayer().getUuid();
        MicrophonePacket packet = event.getPacket();
        VoicechatServerApi api = event.getVoicechat();
        int distance = distance(api, packet.isWhispering());
        byte[] opus = packet.getOpusEncodedData();

        if (opus == null || opus.length == 0) {
            OpusDecoder decoder = decoders.get(id);
            if (decoder != null) decoder.resetState();
            sink.ended(id);
            return;
        }
        if (!sink.wants(id, distance)) {
            forget(id);
            return;
        }

        try {
            OpusDecoder decoder = decoders.computeIfAbsent(id, key -> api.createDecoder());
            short[] samples = decoder.decode(opus);
            if (samples == null || samples.length == 0) return;
            sink.feed(id, MicrophoneAudio.toOutput(samples, false, MicrophoneAudio.OUTPUT_RATE), distance);
        } catch (Exception e) {
            if (warned) return;
            warned = true;
            plugin.getLogger().warning("[CDisc] A microphone packet from Simple Voice Chat could not be decoded: " + e);
        }
    }

    private void forget(UUID id) {
        OpusDecoder decoder = decoders.remove(id);
        if (decoder != null && !decoder.isClosed()) decoder.close();
    }

    private static int distance(VoicechatServerApi api, boolean whispering) {
        double voice = api.getVoiceChatDistance();
        if (!whispering) return (int) Math.max(1, voice);
        try {
            return (int) Math.max(1, api.getServerConfig().getDouble("whisper_distance", voice / 2));
        } catch (Exception e) {
            return (int) Math.max(1, voice / 2);
        }
    }
}
