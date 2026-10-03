package dev.valkdz.cdisc.audio.player;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class TrackExecutor implements Runnable {

    static final int BUFFER_FRAMES = 500;

    static final class SeekRequested extends RuntimeException {
        SeekRequested() {
            super(null, null, false, false);
        }
    }

    static final class Stopped extends RuntimeException {
        Stopped() {
            super(null, null, false, false);
        }
    }

    final AudioTrack track;
    private final AudioPlayer player;
    final ArrayBlockingQueue<AudioFrame> queue = new ArrayBlockingQueue<>(BUFFER_FRAMES);
    private final List<Closeable> resources = new ArrayList<>();
    private volatile boolean stopped;
    private volatile long pendingSeek = -1;
    private volatile long shownPosition = -1;
    private volatile long lastTimecode;
    volatile boolean finished;
    volatile LoadException failure;
    volatile long lastFrameAt = System.nanoTime();
    volatile boolean stuckReported;
    private Thread thread;

    TrackExecutor(AudioTrack track, AudioPlayer player) {
        this.track = track;
        this.player = player;
        this.lastTimecode = track.startPosition();
    }

    @Override
    public void run() {
        synchronized (this) {
            thread = Thread.currentThread();
        }
        long start = track.startPosition();
        try {
            while (!stopped) {
                Playback playback = new Playback(this, start);
                try {
                    track.process(playback);
                } catch (SeekRequested seek) {
                    start = takeSeek();
                    if (start < 0) start = playback.positionMs();
                    continue;
                } finally {
                    closeResources();
                }
                if (!awaitSeek()) break;
                start = takeSeek();
            }
        } catch (Stopped ignored) {
        } catch (InterruptedException e) {
            if (!stopped) fail(new LoadException("The track was interrupted", e));
        } catch (LoadException e) {
            if (!stopped) fail(e);
        } catch (Throwable t) {
            if (!stopped) fail(new LoadException(t.getMessage() == null ? t.toString() : t.getMessage(), t));
        } finally {
            closeResources();
            finished = true;
            synchronized (this) {
                thread = null;
            }
        }
    }

    // The decoder reaches the end while the queue still holds seconds of audio; a seek made
    // then must restart decoding, so the track only finishes once the queue has drained.
    private boolean awaitSeek() throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (stopped) return false;
                if (pendingSeek >= 0) return true;
                if (queue.isEmpty()) {
                    finished = true;
                    return false;
                }
            }
            Thread.sleep(20);
        }
    }

    private void fail(LoadException e) {
        failure = e;
        player.trackException(track, e);
    }

    long position() {
        long seek = pendingSeek;
        if (seek >= 0) return seek;
        long shown = shownPosition;
        return shown >= 0 ? shown : lastTimecode;
    }

    synchronized void seek(long ms) {
        shownPosition = ms;
        pendingSeek = ms;
        queue.clear();
        lastFrameAt = System.nanoTime();
    }

    synchronized long takeSeek() {
        long seek = pendingSeek;
        pendingSeek = -1;
        queue.clear();
        return seek;
    }

    boolean stopped() {
        return stopped;
    }

    void checkpoint() {
        if (stopped) throw new Stopped();
        if (pendingSeek >= 0) throw new SeekRequested();
    }

    void offer(byte[] data, long timecode) throws InterruptedException {
        AudioFrame frame = new AudioFrame(data, timecode);
        while (true) {
            checkpoint();
            if (queue.offer(frame, 50, TimeUnit.MILLISECONDS)) return;
        }
    }

    void provided(AudioFrame frame) {
        lastTimecode = frame.getTimecode();
        long shown = shownPosition;
        if (shown >= 0 && pendingSeek < 0 && Math.abs(frame.getTimecode() - shown) <= 3000) shownPosition = -1;
        lastFrameAt = System.nanoTime();
        stuckReported = false;
    }

    void register(Closeable resource) {
        synchronized (resources) {
            if (!stopped) {
                resources.add(resource);
                return;
            }
        }
        close(resource);
    }

    private void closeResources() {
        List<Closeable> open;
        synchronized (resources) {
            if (resources.isEmpty()) return;
            open = new ArrayList<>(resources);
            resources.clear();
        }
        for (Closeable resource : open) close(resource);
    }

    private static void close(Closeable resource) {
        try {
            resource.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    void stop() {
        stopped = true;
        queue.clear();
        closeResources();
        // The pool reuses this thread for the next track once run() returns.
        synchronized (this) {
            if (thread != null) thread.interrupt();
        }
    }
}
