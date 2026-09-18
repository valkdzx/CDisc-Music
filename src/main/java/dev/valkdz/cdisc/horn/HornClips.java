package dev.valkdz.cdisc.horn;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.engine.TrackLoader;
import dev.valkdz.cdisc.util.ItemUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class HornClips {

    static final long FRAME_MS = 20L;

    private static final long CACHE_BYTES = 64L * 1024 * 1024;
    private static final long DECODE_TIMEOUT_MS = 30_000L;
    private static final long RETRY_AFTER_MS = 30_000L;

    static final class Clip {
        private final List<byte[]> frames = Collections.synchronizedList(new ArrayList<>());
        final int wanted;
        private volatile boolean done;
        private volatile boolean failed;
        private volatile long finishedAt;
        private volatile long bytes;

        Clip(long clipMs) {
            this.wanted = (int) Math.max(1L, (clipMs + FRAME_MS - 1) / FRAME_MS);
        }

        int available() {
            return frames.size();
        }

        byte[] frame(int index) {
            return frames.get(index);
        }

        boolean done() {
            return done;
        }

        boolean failed() {
            return failed;
        }
    }

    private final Main plugin;
    private final Map<String, Clip> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ExecutorService decoders = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "cdisc-horn-decode");
        thread.setDaemon(true);
        return thread;
    });

    HornClips(Main plugin) {
        this.plugin = plugin;
    }

    Clip get(ItemUtils.DiscData data, long clipMs) {
        TrackLoader loader = plugin.getAudioPlayerManager().getTrackLoader();
        int volume = plugin.cdiscConfig().getVolume();
        String key = String.join("\n", data.query(), String.valueOf(data.fallback()),
                String.valueOf(data.fetch()), String.valueOf(loader.isPcmOutput()),
                String.valueOf(volume), String.valueOf(clipMs));

        Clip clip;
        synchronized (cache) {
            clip = cache.get(key);
            if (clip != null && !(clip.failed
                    && System.currentTimeMillis() - clip.finishedAt > RETRY_AFTER_MS)) {
                return clip;
            }
            clip = new Clip(clipMs);
            cache.put(key, clip);
        }

        String resolved = loader.resolveQuery(data.query());
        if (resolved == null) {
            finish(clip);
        } else {
            load(clip, resolved, data.fetch(), data.fallback(), data.title(), data.author(), volume);
        }
        return clip;
    }

    private void load(Clip clip, String resolved, String fetch, String fallback,
                      String title, String author, int volume) {
        TrackLoader loader = plugin.getAudioPlayerManager().getTrackLoader();
        loader.load(resolved, fetch, title, author, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) {
                decoders.submit(() -> decode(clip, track, volume));
            }

            @Override public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    noMatches();
                } else {
                    trackLoaded(playlist.getTracks().get(0));
                }
            }

            @Override public void noMatches() {
                retryOrFail();
            }

            @Override public void loadFailed(FriendlyException e) {
                retryOrFail();
            }

            private void retryOrFail() {
                String next = fallback == null ? null : loader.resolveQuery(fallback);
                if (next != null) {
                    load(clip, next, null, null, title, author, volume);
                } else {
                    finish(clip);
                }
            }
        });
    }

    private void decode(Clip clip, AudioTrack track, int volume) {
        AudioPlayer player = plugin.getAudioPlayerManager().getTrackLoader().createPlayer();
        long deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS;
        try {
            player.setVolume(volume);
            player.playTrack(track);
            while (clip.frames.size() < clip.wanted && System.currentTimeMillis() < deadline) {
                AudioFrame frame;
                try {
                    frame = player.provide(250, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    frame = null;
                }
                if (frame == null) {
                    if (player.getPlayingTrack() == null) break;
                    continue;
                }
                if (frame.isTerminator()) break;
                clip.frames.add(frame.getData());
                clip.bytes += frame.getDataLength();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[CDisc] Could not decode a goat horn sound: " + e.getMessage());
        } finally {
            player.destroy();
            finish(clip);
        }
    }

    private void finish(Clip clip) {
        clip.failed = clip.frames.isEmpty();
        clip.finishedAt = System.currentTimeMillis();
        clip.done = true;
        trim();
    }

    private void trim() {
        synchronized (cache) {
            long total = 0;
            for (Clip clip : cache.values()) total += clip.bytes;

            Iterator<Clip> eldestFirst = cache.values().iterator();
            while (total > CACHE_BYTES && eldestFirst.hasNext()) {
                Clip clip = eldestFirst.next();
                if (!clip.done) continue;
                total -= clip.bytes;
                eldestFirst.remove();
            }
        }
    }

    void shutdown() {
        decoders.shutdownNow();
        synchronized (cache) {
            cache.clear();
        }
    }
}
