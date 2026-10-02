package dev.valkdz.cdisc.audio.player;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

final class TrackExecutor implements Runnable {

    static final int BUFFER_FRAMES = 1500;

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
    private final List<Closeable> resources = new CopyOnWriteArrayList<>();
    private volatile boolean stopped;
    private volatile long pendingSeek = -1;
    private volatile long shownPosition = -1;
    private volatile long lastTimecode;
    volatile boolean finished;
    volatile LoadException failure;
    volatile long lastFrameAt = System.currentTimeMillis();
    volatile boolean stuckReported;
    private volatile Thread thread;

    TrackExecutor(AudioTrack track, AudioPlayer player) {
        this.track = track;
        this.player = player;
        this.lastTimecode = track.startPosition();
    }

    @Override
    public void run() {
        thread = Thread.currentThread();
        long start = track.startPosition();
        try {
            while (!stopped) {
                Playback playback = new Playback(this, start);
                try {
                    track.process(playback);
                    break;
                } catch (SeekRequested seek) {
                    start = takeSeek();
                    if (start < 0) start = playback.positionMs();
                } finally {
                    closeResources();
                }
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
            thread = null;
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

    void seek(long ms) {
        shownPosition = ms;
        pendingSeek = ms;
        queue.clear();
        lastFrameAt = System.currentTimeMillis();
    }

    long peekSeek() {
        return pendingSeek;
    }

    long takeSeek() {
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
        lastFrameAt = System.currentTimeMillis();
        stuckReported = false;
    }

    void register(Closeable resource) {
        resources.add(resource);
        if (stopped) closeResources();
    }

    private void closeResources() {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (IOException | RuntimeException ignored) {
            }
        }
        resources.clear();
    }

    void stop() {
        stopped = true;
        queue.clear();
        closeResources();
        Thread running = thread;
        if (running != null) running.interrupt();
    }
}
