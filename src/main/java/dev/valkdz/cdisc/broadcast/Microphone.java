package dev.valkdz.cdisc.broadcast;

import dev.valkdz.cdisc.voice.VoiceSession;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Microphone {

    public enum Mode {
        HANDHELD,
        BLOCK
    }

    private static final int TALKER_QUEUE = 6;
    private static final int TALKER_PRIME = 2;
    private static final long TALKER_IDLE_MS = 1000L;

    private final UUID id;
    private final MicColor color;
    private volatile Mode mode;
    private volatile Location block;
    private volatile Material blockType;

    private volatile UUID holder;
    private volatile int volume = 100;
    private volatile boolean muted;

    public record Output(VoiceSession session, org.bukkit.block.Block at,
                         java.util.concurrent.atomic.AtomicReference<dev.valkdz.cdisc.speaker.SpeakerSettings> applied) {
    }

    private volatile List<Output> outputs = List.of();
    private volatile Set<UUID> excluded = Set.of();
    private final Map<UUID, Talker> talkers = new ConcurrentHashMap<>();

    private static final class Talker {
        private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
        private boolean primed;
        private volatile long heardAt = System.currentTimeMillis();
    }

    public Microphone(UUID id, MicColor color, Mode mode, Location block, Material blockType) {
        this.id = id;
        this.color = color;
        this.mode = mode;
        this.block = block == null ? null : block.clone();
        this.blockType = blockType;
    }

    public UUID id() {
        return id;
    }

    public MicColor color() {
        return color;
    }

    public Mode mode() {
        return mode;
    }

    public Location block() {
        return block == null ? null : block.clone();
    }

    public Material blockType() {
        return blockType;
    }

    public UUID holder() {
        return holder;
    }

    public void setHolder(UUID holder) {
        this.holder = holder;
    }

    public int volume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(200, volume));
    }

    public boolean muted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    void becomeHandheld() {
        mode = Mode.HANDHELD;
        block = null;
        blockType = null;
        talkers.clear();
    }

    void becomeBlock(Location at, Material type) {
        mode = Mode.BLOCK;
        block = at.clone();
        blockType = type;
        holder = null;
        talkers.clear();
    }

    List<Output> outputs() {
        return outputs;
    }

    List<Output> replaceOutputs(List<Output> fresh) {
        List<Output> old = outputs;
        outputs = List.copyOf(fresh);
        excluded = Set.of();
        return old;
    }

    void hear(UUID talker, byte[] pcm, double gain) {
        if (gain < 1.0D) pcm = scaled(pcm, gain);
        Talker state = talkers.computeIfAbsent(talker, key -> new Talker());
        synchronized (state) {
            while (state.frames.size() >= TALKER_QUEUE) state.frames.pollFirst();
            state.frames.addLast(pcm);
            state.heardAt = System.currentTimeMillis();
        }
    }


    private static byte[] scaled(byte[] pcm, double gain) {
        byte[] out = new byte[pcm.length];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int value = (int) Math.round((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)) * gain);
            out[i] = (byte) value;
            out[i + 1] = (byte) (value >> 8);
        }
        return out;
    }
    void forget(UUID talker) {
        talkers.remove(talker);
    }

    // Runs on the mixer thread every 20 ms; a talker is let through only once two frames
    // wait, so network jitter does not chop every word in half.
    byte[] mix() {
        long now = System.currentTimeMillis();
        int[] sum = null;
        for (Map.Entry<UUID, Talker> entry : talkers.entrySet()) {
            Talker state = entry.getValue();
            byte[] frame;
            synchronized (state) {
                if (!state.primed && state.frames.size() >= TALKER_PRIME) state.primed = true;
                frame = state.primed ? state.frames.pollFirst() : null;
                if (frame == null) state.primed = false;
            }
            if (frame == null) {
                if (now - state.heardAt > TALKER_IDLE_MS) talkers.remove(entry.getKey(), state);
                continue;
            }
            if (sum == null) sum = new int[frame.length / 2];
            for (int i = 0; i < sum.length && i * 2 + 1 < frame.length; i++) {
                sum[i] += (short) ((frame[i * 2] & 0xFF) | (frame[i * 2 + 1] << 8));
            }
        }
        if (sum == null) return null;

        double gain = volume / 100.0D;
        byte[] out = new byte[sum.length * 2];
        for (int i = 0; i < sum.length; i++) {
            int value = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sum[i] * gain)));
            out[i * 2] = (byte) value;
            out[i * 2 + 1] = (byte) (value >> 8);
        }
        return out;
    }

    Set<UUID> talkers() {
        return Set.copyOf(talkers.keySet());
    }

    boolean excludedChanged(Set<UUID> now) {
        if (now.equals(excluded)) return false;
        excluded = now;
        return true;
    }
}
