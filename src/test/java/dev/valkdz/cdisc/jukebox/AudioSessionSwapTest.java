package dev.valkdz.cdisc.jukebox;

import dev.valkdz.cdisc.audio.player.AudioFrame;
import dev.valkdz.cdisc.audio.player.AudioPlayer;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Playback;
import dev.valkdz.cdisc.voice.VoiceSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioSessionSwapTest {

    private static final short ORIGINAL = 1000;
    private static final short MINUS = 2000;

    @Test
    void theOtherVersionTakesOverAtTheSameMomentOfTheSong() throws Exception {
        Frames sent = new Frames();
        AudioPlayer first = new AudioPlayer();
        first.playTrack(new Tone("original", ORIGINAL));
        AudioSession session = new AudioSession(first, sent);
        try {
            Thread.sleep(600);

            // The minus starts 2.5 s later in its file than the original does.
            LongUnaryOperator map = t -> t + 2500;
            AudioPlayer next = new AudioPlayer();
            AudioTrack minus = new Tone("minus", MINUS);
            minus.setPosition(map.applyAsLong(first.getPlayingTrack().getPosition()));
            next.playTrack(minus);
            CountDownLatch done = new CountDownLatch(1);
            session.swapTo(next, map, done::countDown, () -> { });

            assertTrue(done.await(5, TimeUnit.SECONDS));
            Thread.sleep(600);
        } finally {
            session.stop();
        }

        // Frames of the short blend carry both tags mixed, so they are only counted.
        List<short[]> frames = sent.copy();
        long previous = -1;
        int previousIndex = -1;
        int originals = 0;
        int minuses = 0;
        for (int i = 0; i < frames.size(); i++) {
            short[] frame = frames.get(i);
            if (frame[1] != ORIGINAL && frame[1] != MINUS) continue;
            long songMs = frame[1] == MINUS ? frame[0] * 20L - 2500 : frame[0] * 20L;
            if (frame[1] == MINUS) minuses++; else originals++;
            if (previous >= 0) {
                assertEquals(previous + 20L * (i - previousIndex), songMs, 20, "the song jumped at " + songMs + " ms");
            }
            previous = songMs;
            previousIndex = i;
        }
        assertTrue(originals > 10 && minuses > 10, originals + " / " + minuses);
    }

    @Test
    void aPausedJukeboxSwitchesAtOnceAndStaysPaused() throws Exception {
        Frames sent = new Frames();
        AudioPlayer first = new AudioPlayer();
        first.playTrack(new Tone("original", ORIGINAL));
        AudioSession session = new AudioSession(first, sent);
        try {
            Thread.sleep(300);
            first.setPaused(true);
            Thread.sleep(100);
            long pausedAt = first.getPlayingTrack().getPosition();

            AudioPlayer next = new AudioPlayer();
            next.playTrack(new Tone("minus", MINUS));
            CountDownLatch done = new CountDownLatch(1);
            session.swapTo(next, t -> t + 2500, done::countDown, () -> { });

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(session.getPlayer() == next && next.isPaused());
            assertEquals(pausedAt + 2500, session.positionOf(next.getPlayingTrack()), 40);
        } finally {
            session.stop();
        }
    }

    // Every frame says where it is: the left sample counts frames, the right one names the version.
    private static final class Tone extends AudioTrack {
        private final short tag;

        Tone(String name, short tag) {
            super(new AudioTrackInfo(name, "test", 120_000, name, false, name));
            this.tag = tag;
        }

        @Override
        protected AudioTrack makeShallowClone() {
            return new Tone(trackInfo.identifier, tag);
        }

        @Override
        public void process(Playback playback) throws Exception {
            for (long at = playback.startMs() / 20 * 20; at < getDuration(); at += 20) {
                playback.checkpoint();
                byte[] frame = new byte[AudioFrame.BYTES];
                short count = (short) (at / 20);
                for (int i = 0; i + 3 < frame.length; i += 4) {
                    frame[i] = (byte) count;
                    frame[i + 1] = (byte) (count >> 8);
                    frame[i + 2] = (byte) tag;
                    frame[i + 3] = (byte) (tag >> 8);
                }
                playback.writePcm(frame, at);
            }
        }
    }

    private static final class Frames implements VoiceSession {
        private final List<short[]> frames = new ArrayList<>();

        @Override
        public synchronized void sendFrame(byte[] pcm) {
            frames.add(new short[]{(short) ((pcm[0] & 0xFF) | (pcm[1] << 8)), (short) ((pcm[2] & 0xFF) | (pcm[3] << 8))});
        }

        synchronized List<short[]> copy() {
            return new ArrayList<>(frames);
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }

        @Override
        public void setDistance(float distance) {
        }
    }
}
