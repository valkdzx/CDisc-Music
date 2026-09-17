package dev.valkdz.cdisc.audio.sabr;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.List;

public final class ChunkedHttpStream extends SeekableInputStream {

    // googlevideo throttles any range over 10 MB to ~30 KB/s from the first byte,
    // which turns every seek in a long video into a multi-second wait.
    static final long CHUNK_BYTES = 8L * 1024 * 1024;

    private static final long MAX_SKIP_DISTANCE = 512L * 1024;

    private static final int MAX_ATTEMPTS = 3;

    private final HttpInterface httpInterface;
    private final URI url;

    private CloseableHttpResponse response;
    private InputStream content;
    private long position;
    private long chunkEnd;

    public ChunkedHttpStream(HttpInterface httpInterface, URI url, long contentLength) {
        super(contentLength, MAX_SKIP_DISTANCE);
        this.httpInterface = httpInterface;
        this.url = url;
    }

    @Override
    public long getPosition() {
        return position;
    }

    @Override
    public boolean canSeekHard() {
        return true;
    }

    @Override
    protected void seekHard(long target) {
        release();
        position = target;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        if (length == 0) return 0;

        IOException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (position >= contentLength) return -1;

            try {
                if (content == null) open();

                int wanted = (int) Math.min(length, chunkEnd - position);
                int taken = content.read(destination, offset, wanted);
                if (taken > 0) {
                    position += taken;
                    if (position >= chunkEnd) release();
                    return taken;
                }
                last = new IOException("The connection closed at byte " + position
                        + " of " + contentLength);
            } catch (IOException e) {
                last = e;
            }
            release();
        }
        throw last;
    }

    private void open() throws IOException {
        long end = Math.min(position + CHUNK_BYTES, contentLength);

        HttpGet request = new HttpGet(url);
        request.setHeader("Range", "bytes=" + position + "-" + (end - 1));

        CloseableHttpResponse opened = httpInterface.execute(request);
        int status = opened.getStatusLine().getStatusCode();

        boolean whole = status == 200 && position == 0;
        if (status != 206 && !whole) {
            opened.close();
            throw new IOException("Server answered " + status + " for bytes " + position
                    + "-" + (end - 1));
        }

        response = opened;
        content = opened.getEntity().getContent();
        chunkEnd = whole ? contentLength : end;
    }

    @Override
    public long skip(long count) throws IOException {
        byte[] scratch = new byte[8192];
        long skipped = 0;

        while (skipped < count) {
            int taken = read(scratch, 0, (int) Math.min(scratch.length, count - skipped));
            if (taken < 0) break;
            skipped += taken;
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return content == null ? 0 : (int) Math.min(content.available(), chunkEnd - position);
    }

    @Override
    public List<AudioTrackInfoProvider> getTrackInfoProviders() {
        return Collections.emptyList();
    }

    @Override
    public void close() {
        release();
    }

    private void release() {
        CloseableHttpResponse open = response;
        response = null;
        content = null;
        if (open == null) return;

        try {
            open.close();
        } catch (IOException ignored) {
        }
    }
}
