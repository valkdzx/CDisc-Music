package dev.valkdz.cdisc.audio.player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AudioPlayer {

    private static final long STUCK_THRESHOLD_MS = 10_000L;
    private static final long STUCK_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(STUCK_THRESHOLD_MS);
    private static final Logger LOG = Logger.getLogger("CDisc");
    private static final ExecutorService THREADS = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "cdisc-track");
        thread.setDaemon(true);
        return thread;
    });

    private final List<AudioEventAdapter> listeners = new CopyOnWriteArrayList<>();
    private final Object transition = new Object();
    private volatile TrackExecutor current;
    private volatile boolean paused;
    private volatile int volume = 100;

    public void addListener(AudioEventAdapter listener) {
        listeners.add(listener);
    }

    public void removeListener(AudioEventAdapter listener) {
        listeners.remove(listener);
    }

    public AudioTrack getPlayingTrack() {
        TrackExecutor executor = current;
        return executor == null ? null : executor.track;
    }

    // Held across the listener calls so two callers cannot interleave start and end events;
    // the provide() path never takes it, so a listener may still play or stop from there.
    public void playTrack(AudioTrack track) {
        synchronized (transition) {
            TrackExecutor previous;
            TrackExecutor next = null;
            synchronized (this) {
                previous = current;
                if (track != null) {
                    next = new TrackExecutor(track, this);
                    track.bind(next);
                }
                current = next;
            }
            if (previous != null) {
                previous.stop();
                fireEnd(previous.track, AudioTrackEndReason.REPLACED);
            }
            if (next != null) {
                for (AudioEventAdapter listener : listeners) {
                    try {
                        listener.onTrackStart(this, track);
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "[CDisc] A track start listener failed", e);
                    }
                }
                THREADS.execute(next);
            }
        }
    }

    public void stopTrack() {
        synchronized (transition) {
            TrackExecutor previous;
            synchronized (this) {
                previous = current;
                current = null;
            }
            if (previous != null) {
                previous.stop();
                fireEnd(previous.track, AudioTrackEndReason.STOPPED);
            }
        }
    }

    public void destroy() {
        stopTrack();
        listeners.clear();
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        TrackExecutor executor = current;
        if (executor != null) executor.lastFrameAt = System.nanoTime();
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(1000, volume));
    }

    public AudioFrame provide() {
        if (paused) return null;
        TrackExecutor executor = current;
        if (executor == null) return null;
        AudioFrame frame = executor.queue.poll();
        if (frame != null) return deliver(executor, frame);
        return idle(executor);
    }

    public AudioFrame provide(long timeout, TimeUnit unit) throws TimeoutException, InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            TrackExecutor executor = current;
            if (executor == null) return null;
            if (!paused) {
                long left = deadline - System.nanoTime();
                AudioFrame frame = executor.queue.poll(Math.max(0, Math.min(left, 20_000_000L)), TimeUnit.NANOSECONDS);
                if (frame != null) return deliver(executor, frame);
                AudioFrame ended = idle(executor);
                if (ended != null) return ended;
                if (current != executor) return null;
            } else {
                Thread.sleep(5);
            }
            if (System.nanoTime() >= deadline) throw new TimeoutException();
        }
    }

    private AudioFrame deliver(TrackExecutor executor, AudioFrame frame) {
        executor.provided(frame);
        int v = volume;
        if (v == 100) return frame;
        byte[] data = frame.getData();
        for (int i = 0; i + 1 < data.length; i += 2) {
            int s = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            int scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s * v / 100));
            data[i] = (byte) scaled;
            data[i + 1] = (byte) (scaled >> 8);
        }
        return frame;
    }

    private AudioFrame idle(TrackExecutor executor) {
        if (executor.finished && executor.queue.isEmpty()) {
            boolean ended;
            synchronized (this) {
                ended = current == executor;
                if (ended) current = null;
            }
            if (ended) {
                fireEnd(executor.track, executor.failure != null
                        ? AudioTrackEndReason.LOAD_FAILED : AudioTrackEndReason.FINISHED);
            }
            return null;
        }
        if (!executor.stuckReported && System.nanoTime() - executor.lastFrameAt > STUCK_THRESHOLD_NS) {
            executor.stuckReported = true;
            for (AudioEventAdapter listener : listeners) {
                try {
                    listener.onTrackStuck(this, executor.track, STUCK_THRESHOLD_MS);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "[CDisc] A track stuck listener failed", e);
                }
            }
        }
        return null;
    }

    void trackException(AudioTrack track, LoadException exception) {
        for (AudioEventAdapter listener : listeners) {
            try {
                listener.onTrackException(this, track, exception);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "[CDisc] A track exception listener failed", e);
            }
        }
    }

    private void fireEnd(AudioTrack track, AudioTrackEndReason reason) {
        for (AudioEventAdapter listener : listeners) {
            try {
                listener.onTrackEnd(this, track, reason);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "[CDisc] A track end listener failed", e);
            }
        }
    }
}
