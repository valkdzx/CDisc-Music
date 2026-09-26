package dev.valkdz.cdisc.audio.hls;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class HlsMp3Stream extends InputStream {

    private final HttpInterface http;
    private final HlsPlaylist playlist;
    private final boolean ownsHttp;
    private int next;
    private InputStream current = InputStream.nullInputStream();

    public HlsMp3Stream(HttpInterface http, HlsPlaylist playlist, int from, boolean ownsHttp) {
        this.http = http;
        this.playlist = playlist;
        this.next = from;
        this.ownsHttp = ownsHttp;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;

        while (true) {
            int read = current.read(buffer, offset, length);
            if (read > 0) return read;
            if (next >= playlist.segments().size()) return -1;
            current = new ByteArrayInputStream(playlist.audioOf(http, next++));
        }
    }

    @Override
    public void close() throws IOException {
        next = playlist.segments().size();
        if (ownsHttp) http.close();
    }
}
