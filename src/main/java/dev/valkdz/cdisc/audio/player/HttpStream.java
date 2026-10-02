package dev.valkdz.cdisc.audio.player;

import dev.valkdz.cdisc.audio.media.MediaInput;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;

public final class HttpStream extends MediaInput {

    private static final long MAX_SKIP = 512L * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_REFRESHES = 2;

    private String url;
    private final String[] headers;
    private final long chunkBytes;
    private final Callable<String> refresh;
    private long length;
    private boolean ranged;
    private long position;
    private long chunkEnd = Long.MAX_VALUE;
    private InputStream content;
    private int refreshes;
    private String contentType;

    public HttpStream(String url, long length, String... headers) {
        this(url, length, 0, null, headers);
    }

    // chunkBytes > 0 asks for bounded ranges: googlevideo throttles any range over 10 MB.
    public HttpStream(String url, long length, long chunkBytes, Callable<String> refresh, String... headers) {
        this.url = url;
        this.length = length;
        this.chunkBytes = chunkBytes;
        this.refresh = refresh;
        this.headers = headers;
        this.ranged = length > 0;
    }

    public String contentType() throws IOException {
        if (content == null && contentType == null) open();
        return contentType;
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public long length() {
        if (length < 0 && content == null) {
            try {
                open();
            } catch (IOException ignored) {
            }
        }
        return length;
    }

    @Override
    public boolean canSeek() {
        length();
        return ranged && length > 0;
    }

    @Override
    public void seek(long target) throws IOException {
        if (target == position) return;
        if (content != null && target > position && target - position <= MAX_SKIP && target < chunkEnd) {
            skipFully(target - position);
            return;
        }
        release();
        position = target;
    }

    @Override
    public int read(byte[] buffer, int offset, int count) throws IOException {
        if (count == 0) return 0;
        IOException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (length >= 0 && position >= length) return -1;
            try {
                if (content == null) open();
                int wanted = (int) Math.min(count, chunkEnd - position);
                int taken = content.read(buffer, offset, wanted);
                if (taken > 0) {
                    position += taken;
                    if (position >= chunkEnd) release();
                    return taken;
                }
                if (length < 0) return -1;
                last = new IOException("The connection closed at byte " + position + " of " + length);
            } catch (IOException e) {
                last = e;
            }
            release();
            if (!ranged) break;
        }
        throw last;
    }

    private void open() throws IOException {
        long end = chunkBytes > 0 && length > 0 ? Math.min(position + chunkBytes, length) - 1 : -1;
        HttpResponse<InputStream> response = Http.open(url, position, end, headers);
        int status = response.statusCode();
        if ((status == 403 || status == 410) && refresh != null && refreshes < MAX_REFRESHES) {
            response.body().close();
            refreshes++;
            url = fresh();
            open();
            return;
        }
        if (status == 416 && length > 0 && position >= length) {
            response.body().close();
            content = InputStream.nullInputStream();
            chunkEnd = Long.MAX_VALUE;
            return;
        }
        if (status != 206 && !(status == 200 && position == 0)) {
            response.body().close();
            throw new IOException("HTTP " + status + " for bytes " + position + "-" + (end >= 0 ? end : ""));
        }
        contentType = response.headers().firstValue("Content-Type").orElse(contentType);
        if (status == 206) {
            ranged = true;
            String range = response.headers().firstValue("Content-Range").orElse("");
            int slash = range.lastIndexOf('/');
            if (slash >= 0 && length < 0) {
                try {
                    length = Long.parseLong(range.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            chunkEnd = end >= 0 ? end + 1 : Long.MAX_VALUE;
        } else {
            if (length < 0) length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            ranged = ranged || response.headers().firstValue("Accept-Ranges").map(v -> v.contains("bytes")).orElse(false);
            chunkEnd = Long.MAX_VALUE;
        }
        content = response.body();
    }

    private String fresh() throws IOException {
        try {
            return refresh.call();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not get a fresh address for the stream: " + e.getMessage(), e);
        }
    }

    private void release() {
        InputStream open = content;
        content = null;
        if (open == null) return;
        try {
            open.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        release();
    }
}
