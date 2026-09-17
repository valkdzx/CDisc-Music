package dev.valkdz.cdisc.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import dev.valkdz.cdisc.voice.VoiceSession;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AudioSession {
    private final AudioPlayer player;
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

            player.provide();
            lead.clear();
            priming = true;
            return;
        }

        if (trackChanged()) {
            lead.clear();
            priming = true;
        }

        boolean stream = isCurrentStream();
        int cap = stream ? STREAM_LEAD_CAP : DISC_LEAD_CAP;

        AudioFrame frame;
        while (lead.size() < cap && (frame = player.provide()) != null) {
            lead.addLast(frame.getData());
        }

        if (priming) {
            int need = stream ? STREAM_PREBUFFER : 0;
            if (lead.size() <= need) return;
            priming = false;
        }

        byte[] data = lead.pollFirst();
        if (data != null) {
            send(voiceSession, data);
            for (VoiceSession speaker : speakerOutputs) {
                send(speaker, data);
            }
        } else if (stream) {

            priming = true;
        }
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
