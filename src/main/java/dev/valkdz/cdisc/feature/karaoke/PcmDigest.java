package dev.valkdz.cdisc.feature.karaoke;

import dev.valkdz.cdisc.audio.player.AudioEventAdapter;
import dev.valkdz.cdisc.audio.player.AudioFrame;
import dev.valkdz.cdisc.audio.player.AudioPlayer;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.LoadException;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PcmDigest {

    private static final int STEP = AudioFrame.SAMPLE_RATE / BeatMatch.RATE;
    private static final int MIN_SAMPLES = 30 * BeatMatch.RATE;
    private static final double VOICE_DROP_DB = 2;
    private static final double CENTER_DROP_DB = 1;
    private static final double MONO_DB = -20;

    private PcmDigest() {
    }

    // pcm: mono at BeatMatch.RATE. The energies, over the whole read, describe the mix: the voice
    // band (300-3400 Hz) in the middle and at the sides of the stereo image, and the bass below 180 Hz.
    public record Digest(float[] pcm, double voiceMid, double voiceSide, double bass) {
    }

    public static float[] decode(AudioTrack track, long maxMs, long timeoutMs) throws IOException, InterruptedException {
        return read(track, maxMs, timeoutMs).pcm();
    }

    // A cut made from the original with only its vocals taken out lost more in the voice band than
    // in the bass, and more in the middle, where the lead voice sits, than at the sides.
    public static boolean vocalsTakenOut(Digest original, Digest other) {
        double voice = db(other.voiceMid() / original.voiceMid());
        double sides = db(other.voiceSide() / original.voiceSide());
        double bass = db(other.bass() / original.bass());
        boolean stereo = db(original.voiceSide() / original.voiceMid()) > MONO_DB;
        return voice - bass <= -VOICE_DROP_DB && (!stereo || sides - voice >= CENTER_DROP_DB);
    }

    private static double db(double ratio) {
        return 10 * Math.log10(Math.max(ratio, 1e-12));
    }

    public static Digest read(AudioTrack track, long maxMs, long timeoutMs) throws IOException, InterruptedException {
        long length = track.getDuration();
        long wanted = length > 0 && length != AudioTrackInfo.UNKNOWN_LENGTH ? Math.min(maxMs, length + 5_000) : maxMs;
        float[] out = new float[(int) (wanted * BeatMatch.RATE / 1000)];
        int filled = 0;
        Biquad[] filters = {lowPass(), lowPass(), lowPass(), lowPass()};
        Biquad[] voiceMid = voiceBand();
        Biquad[] voiceSide = voiceBand();
        Biquad[] bass = {Biquad.lowPass(180, BeatMatch.RATE), Biquad.lowPass(180, BeatMatch.RATE)};
        double midEnergy = 0;
        double sideEnergy = 0;
        double bassEnergy = 0;

        AudioPlayer player = new AudioPlayer();
        LoadException[] failure = new LoadException[1];
        player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackException(AudioPlayer p, AudioTrack t, LoadException e) {
                failure[0] = e;
            }
        });
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            player.playTrack(track);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Decoding took longer than " + timeoutMs / 1000 + " s");
                AudioFrame frame;
                try {
                    frame = player.provide(Math.min(remaining, TimeUnit.SECONDS.toNanos(5)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    continue;
                }
                if (frame == null) {
                    if (player.getPlayingTrack() == null) break;
                    continue;
                }
                int at = (int) Math.min(out.length, frame.getTimecode() * BeatMatch.RATE / 1000);
                if (at >= out.length) break;
                byte[] data = frame.getData();
                for (int i = 0, s = 0; i + 3 < data.length; i += 4, s++) {
                    double l = filters[1].apply(filters[0].apply(sample(data, i)));
                    double r = filters[3].apply(filters[2].apply(sample(data, i + 2)));
                    if (s % STEP != 0) continue;
                    int index = at + s / STEP;
                    if (index >= out.length) break;
                    double mid = (l + r) / 2;
                    out[index] = (float) mid;
                    filled = Math.max(filled, index + 1);
                    double v = voiceMid[1].apply(voiceMid[0].apply(mid));
                    double w = voiceSide[1].apply(voiceSide[0].apply((l - r) / 2));
                    double b = bass[1].apply(bass[0].apply(mid));
                    midEnergy += v * v;
                    sideEnergy += w * w;
                    bassEnergy += b * b;
                }
            }
        } finally {
            player.destroy();
        }
        if (filled < MIN_SAMPLES) {
            String why = failure[0] != null ? failure[0].getMessage() : "only " + filled / BeatMatch.RATE + " s decoded";
            throw new IOException(why, failure[0]);
        }
        return new Digest(Arrays.copyOf(out, filled), midEnergy, sideEnergy, bassEnergy);
    }

    private static double sample(byte[] data, int at) {
        return (short) ((data[at] & 0xFF) | (data[at + 1] << 8)) / 32768.0;
    }

    private static Biquad lowPass() {
        return Biquad.lowPass(3600, AudioFrame.SAMPLE_RATE);
    }

    private static Biquad[] voiceBand() {
        return new Biquad[]{Biquad.highPass(300, BeatMatch.RATE), Biquad.lowPass(3400, BeatMatch.RATE)};
    }
}
