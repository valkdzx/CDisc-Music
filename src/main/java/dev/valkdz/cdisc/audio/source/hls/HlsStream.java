package dev.valkdz.cdisc.audio.source.hls;

import dev.valkdz.cdisc.audio.media.MediaInput;
import dev.valkdz.cdisc.audio.player.Http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;

// The audio of an HLS playlist as one continuous stream; a live playlist is polled for new segments.
public final class HlsStream extends MediaInput {

    private static final int LIVE_LEAD_SEGMENTS = 3;
    private static final int MAX_MISSES = 10;

    private final Callable<String> playlistUrl;
    private HlsPlaylist playlist;
    private String url;
    private int next;
    private long lastSequence = -1;
    private InputStream current;
    private long position;
    private volatile boolean closed;

    public HlsStream(HlsPlaylist playlist, int from) throws IOException {
        this.playlist = playlist;
        this.playlistUrl = null;
        this.next = from;
        this.current = new ByteArrayInputStream(playlist.prefixFor(from));
    }

    public static HlsStream live(String url, Callable<String> refresh, Http.Guard guard, String... headers)
            throws IOException {
        HlsPlaylist playlist = HlsPlaylist.fetchMedia(url, guard, headers);
        return new HlsStream(playlist, url, refresh);
    }

    private HlsStream(HlsPlaylist playlist, String url, Callable<String> refresh) throws IOException {
        this.playlist = playlist;
        this.url = url;
        this.playlistUrl = refresh;
        this.next = Math.max(0, playlist.segments().size() - LIVE_LEAD_SEGMENTS);
        this.current = new ByteArrayInputStream(playlist.prefixFor(0));
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        while (!closed) {
            int read = current.read(buffer, offset, length);
            if (read > 0) {
                position += read;
                return read;
            }
            byte[] segment = nextSegment();
            if (segment == null) return -1;
            current = new ByteArrayInputStream(segment);
        }
        return -1;
    }

    private byte[] nextSegment() throws IOException {
        if (url == null) {
            return next < playlist.segments().size() ? playlist.audioOf(next++) : null;
        }
        int misses = 0;
        while (!closed) {
            while (next < playlist.segments().size()) {
                HlsPlaylist.Segment segment = playlist.segments().get(next++);
                if (segment.sequence() <= lastSequence || isAdvert(segment)) continue;
                lastSequence = segment.sequence();
                return playlist.audioOf(next - 1);
            }
            if (playlist.ended()) return null;
            sleep(Math.max(500, playlist.targetDurationMs() / 2));
            try {
                playlist = playlist.refetch(url);
                misses = 0;
            } catch (IOException e) {
                if (++misses > MAX_MISSES) throw e;
                url = fresh(e);
            }
            next = 0;
        }
        return null;
    }

    private static boolean isAdvert(HlsPlaylist.Segment segment) {
        return segment.title() != null && segment.title().contains("Amazon");
    }

    private String fresh(IOException failure) throws IOException {
        if (playlistUrl == null) return url;
        try {
            String fresh = playlistUrl.call();
            return fresh == null ? url : fresh;
        } catch (Exception e) {
            failure.addSuppressed(e);
            throw failure;
        }
    }

    private void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the live stream", e);
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
