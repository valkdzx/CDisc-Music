package dev.valkdz.cdisc.jukebox;

import dev.valkdz.cdisc.audio.player.AudioFrame;
import dev.valkdz.cdisc.audio.player.AudioPlayer;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.voice.VoiceSession;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public class AudioSession {
    private volatile AudioPlayer player;
    private volatile VoiceSession voiceSession;

    private final java.util.List<VoiceSession> speakerOutputs = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final ScheduledExecutorService pump;

    private volatile String discTitle;
    private volatile String discAuthor;

    private volatile long lastStreamReconnectMs = 0L;
    private volatile int rapidStreamReconnects = 0;

    private final ArrayDeque<byte[]> lead = new ArrayDeque<>();
    private boolean priming = true;
    private String lastTrackId;
    private long lastPumpFailureMs;

    private static final int STREAM_LEAD_CAP = 1500;
    private static final int STREAM_PREBUFFER = 75;
    private static final int DISC_LEAD_CAP = 2;

    private record Staged(AudioPlayer next, long fadeMs, String title, String author,
                          BooleanSupplier stillValid, Runnable handoff) {
    }

    private final AtomicReference<Staged> staged = new AtomicReference<>();
    private volatile long nearEndLeadMs = -1L;
    private volatile Runnable onNearEnd;
    private AudioTrack nearEndFiredFor;

    private static final long SEEK_LANDING_MS = 3000L;
    private static final long SEEK_PATIENCE_MS = 15_000L;
    private volatile AudioTrack seekTrack;
    private volatile long seekTarget = -1L;
    private volatile long seekSince;

    private AudioPlayer outgoing;
    private final ArrayDeque<byte[]> outLead = new ArrayDeque<>();
    private int fadeFrames;
    private int fadeDone;

    public AudioSession(AudioPlayer player, VoiceSession voiceSession) {
        this(player, voiceSession, null, null);
    }

    public AudioSession(AudioPlayer player, VoiceSession voiceSession, String discTitle, String discAuthor) {
        this.player = player;
        this.voiceSession = voiceSession;
        this.discTitle = discTitle;
        this.discAuthor = discAuthor;
        this.pump = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "cdisc-audio-pump");
            thread.setDaemon(true);
            return thread;
        });
        startPump();
    }

    public void setDiscMeta(String discTitle, String discAuthor) {
        this.discTitle = discTitle;
        this.discAuthor = discAuthor;
    }

    public boolean allowStreamReconnect() {
        long now = System.currentTimeMillis();
        if (now - lastStreamReconnectMs < 3000L) {
            rapidStreamReconnects++;
        } else {
            rapidStreamReconnects = 0;
        }
        lastStreamReconnectMs = now;
        return rapidStreamReconnects < 5;
    }

    private void startPump() {
        // An exception escaping a scheduleAtFixedRate task cancels every later run,
        // which silences the jukebox for good with nothing in the log.
        pump.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Throwable t) {
                reportPumpFailure(t);
            }
        }, 0, 20, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (voiceSession.isClosed()) {
            pump.shutdownNow();
            return;
        }

        if (player.isPaused()) {
            // A paused player reads no frame, so a seek made meanwhile can only land on resume.
            if (seekTarget >= 0) seekSince = System.currentTimeMillis();
            player.provide();
            lead.clear();
            priming = true;
            endFade();
            return;
        }

        watchEnd();

        if (trackChanged()) {
            lead.clear();
            priming = true;
        }

        boolean stream = isCurrentStream();
        int cap = stream ? STREAM_LEAD_CAP : DISC_LEAD_CAP;

        AudioFrame frame;
        while (lead.size() < cap && (frame = player.provide()) != null) {
            landSeek(frame);
            lead.addLast(frame.getData());
        }

        if (priming) {
            int need = stream ? STREAM_PREBUFFER : 0;
            if (lead.size() <= need) return;
            priming = false;
        }

        byte[] data = lead.pollFirst();
        if (outgoing != null) data = fade(data);
        if (data != null) {
            send(voiceSession, data);
            for (VoiceSession speaker : speakerOutputs) {
                send(speaker, data);
            }
        } else if (stream) {

            priming = true;
        }
    }

    // Until the first frame from the new place arrives, the frames already read report the old
    // place, so the bar, the lyrics and a second seek would all start from there.
    public void seeking(AudioTrack track, long target) {
        seekSince = System.currentTimeMillis();
        seekTrack = track;
        seekTarget = target;
    }

    public long positionOf(AudioTrack track) {
        long target = seekTarget;
        if (target >= 0 && track == seekTrack
                && System.currentTimeMillis() - seekSince < SEEK_PATIENCE_MS) {
            return target;
        }
        return track.getPosition();
    }

    private void landSeek(AudioFrame frame) {
        long target = seekTarget;
        if (target >= 0 && Math.abs(frame.getTimecode() - target) <= SEEK_LANDING_MS) seekTarget = -1L;
    }

    public void watchEnd(long leadMs, Runnable callback) {
        this.onNearEnd = callback;
        this.nearEndLeadMs = leadMs;
    }

    public void stage(AudioPlayer next, long fadeMs, String title, String author,
                      BooleanSupplier stillValid, Runnable handoff) {
        Staged previous = staged.getAndSet(new Staged(next, fadeMs, title, author, stillValid, handoff));
        if (previous != null) previous.next().destroy();
    }

    public void dropStaged() {
        Staged previous = staged.getAndSet(null);
        if (previous != null) previous.next().destroy();
    }

    private void watchEnd() {
        AudioTrack track = player.getPlayingTrack();
        long leadMs = nearEndLeadMs;
        if (track == null || leadMs < 0 || track.getInfo().isStream) return;

        long duration = track.getDuration();
        if (duration <= 0 || duration == Long.MAX_VALUE) return;
        long remaining = duration - positionOf(track);

        if (nearEndFiredFor == track) {
            if (remaining > leadMs + 2000L) {
                nearEndFiredFor = null;
                dropStaged();
            }
        } else if (remaining <= leadMs && remaining > 1000L && outgoing == null) {
            nearEndFiredFor = track;
            Runnable callback = onNearEnd;
            if (callback != null) callback.run();
        }

        Staged ready = staged.get();
        if (ready != null && outgoing == null && remaining <= ready.fadeMs()) handOff(remaining);
    }

    private void handOff(long remaining) {
        Staged next = staged.getAndSet(null);
        if (next == null) return;
        AudioTrack incoming = next.next().getPlayingTrack();
        if (incoming == null || remaining < 500L || !next.stillValid().getAsBoolean()) {
            next.next().destroy();
            return;
        }

        // The old player's end event must find it no longer current, or the queue advances twice.
        outgoing = player;
        player = next.next();
        outLead.addAll(lead);
        lead.clear();
        lastTrackId = incoming.getInfo().identifier;
        discTitle = next.title();
        discAuthor = next.author();
        fadeFrames = (int) Math.max(1, Math.min(remaining, next.fadeMs()) / 20);
        fadeDone = 0;
        player.setPaused(false);
        next.handoff().run();
    }

    private byte[] fade(byte[] in) {
        byte[] out = outLead.pollFirst();
        if (out == null) {
            AudioFrame frame = outgoing.provide();
            if (frame != null) out = frame.getData();
        }

        double t = Math.min(1.0D, (fadeDone + 1) / (double) fadeFrames);
        byte[] mixed = mix(in, Math.sin(t * Math.PI / 2), out, Math.cos(t * Math.PI / 2));
        if (++fadeDone >= fadeFrames || (out == null && outgoing.getPlayingTrack() == null)) endFade();
        return mixed;
    }

    private static byte[] mix(byte[] a, double gainA, byte[] b, double gainB) {
        if (a == null && b == null) return null;
        int length = Math.max(a == null ? 0 : a.length, b == null ? 0 : b.length);
        byte[] result = new byte[length];
        for (int i = 0; i + 1 < length; i += 2) {
            long value = Math.round(sample(a, i) * gainA + sample(b, i) * gainB);
            int clamped = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
            result[i] = (byte) clamped;
            result[i + 1] = (byte) (clamped >> 8);
        }
        return result;
    }

    private static int sample(byte[] pcm, int at) {
        if (pcm == null || at + 1 >= pcm.length) return 0;
        return (pcm[at] & 0xFF) | (pcm[at + 1] << 8);
    }

    private void endFade() {
        AudioPlayer old = outgoing;
        if (old == null) return;
        outgoing = null;
        outLead.clear();
        old.stopTrack();
        old.destroy();
    }

    private void send(VoiceSession output, byte[] data) {
        try {
            output.sendFrame(data);
        } catch (RuntimeException e) {
            reportPumpFailure(e);
        }
    }

    private void reportPumpFailure(Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastPumpFailureMs < 60_000L) return;
        lastPumpFailureMs = now;
        dev.valkdz.cdisc.Main.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                "[CDisc] A jukebox audio frame could not be delivered; playback continues", t);
    }

    private boolean isCurrentStream() {
        AudioTrack track = player.getPlayingTrack();
        return track != null && track.getInfo().isStream;
    }

    private boolean trackChanged() {
        AudioTrack track = player.getPlayingTrack();
        if (track == null) return false;
        String id = track.getInfo().identifier;
        if (!Objects.equals(id, lastTrackId)) {
            lastTrackId = id;
            return true;
        }
        return false;
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    public VoiceSession getVoiceSession() {
        return voiceSession;
    }

    public void replaceVoiceSession(VoiceSession fresh) {
        if (fresh == null || fresh == voiceSession) return;

        VoiceSession old = voiceSession;
        voiceSession = fresh;
        old.close();
    }

    public void addSpeakerOutput(VoiceSession output) {
        if (output != null) speakerOutputs.add(output);
    }

    public void removeSpeakerOutput(VoiceSession output) {
        if (output != null && speakerOutputs.remove(output)) {
            output.close();
        }
    }

    public java.util.List<VoiceSession> allOutputs() {
        java.util.List<VoiceSession> all = new java.util.ArrayList<>(speakerOutputs.size() + 1);
        all.add(voiceSession);
        all.addAll(speakerOutputs);
        return all;
    }

    public String getDiscTitle() {
        return discTitle;
    }

    public String getDiscAuthor() {
        return discAuthor;
    }

    public void stop() {
        pump.shutdownNow();
        try {
            pump.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            dropStaged();
            endFade();
            player.stopTrack();
            player.destroy();
            voiceSession.close();
            for (VoiceSession speaker : speakerOutputs) {
                speaker.close();
            }
            speakerOutputs.clear();
        }
    }
}
