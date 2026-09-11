package dev.valkdz.cdisc.audio.sabr;

import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public final class SabrSeekableInputStream extends SeekableInputStream {

    private static final long MAX_SKIP_DISTANCE = 16L * 1024 * 1024;

    private static final int REWIND_WINDOW = 256 * 1024;

    private final Supplier<? extends InputStream> opener;

    private InputStream delegate;
    private long position;

    private byte[] rewind;
    private int rewindHeld;

    private int rewindCursor = -1;

    public SabrSeekableInputStream(long contentLength, Supplier<? extends InputStream> opener) {
        super(contentLength, MAX_SKIP_DISTANCE);
        this.opener = opener;
    }

    private InputStream delegate() {
        if (delegate == null) delegate = opener.get();
        return delegate;
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
    protected void seekHard(long target) throws IOException {
        if (target < position) {
            if (canRewindTo(target)) {
                rewindCursor = (int) target;
                position = target;
                return;
            }
            restart();
        }
        skipFully(target - position);
    }

    private boolean canRewindTo(long target) {
        return rewind != null && target <= rewindHeld && position <= rewindHeld;
    }

    private void restart() throws IOException {
        if (delegate != null) delegate.close();
        delegate = null;
        position = 0;
        rewindHeld = 0;
        rewindCursor = -1;
    }

    private boolean replaying() {
        return rewindCursor >= 0 && rewindCursor < rewindHeld;
    }

    @Override
    public int read() throws IOException {
        if (replaying()) {
            int value = rewind[rewindCursor++] & 0xFF;
            position++;
            if (rewindCursor >= rewindHeld) rewindCursor = -1;
            return value;
        }

        rewindCursor = -1;
        int value = delegate().read();
        if (value >= 0) {
            capture((byte) value);
            position++;
        }
        return value;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        if (length == 0) return 0;

        if (replaying()) {
            int taken = Math.min(length, rewindHeld - rewindCursor);
            System.arraycopy(rewind, rewindCursor, destination, offset, taken);
            rewindCursor += taken;
            position += taken;
            if (rewindCursor >= rewindHeld) rewindCursor = -1;
            return taken;
        }

        rewindCursor = -1;
        int taken = delegate().read(destination, offset, length);
        if (taken > 0) {
            capture(destination, offset, taken);
            position += taken;
        }
        return taken;
    }

    private void capture(byte[] source, int offset, int length) {
        if (position != rewindHeld || rewindHeld >= REWIND_WINDOW) return;

        if (rewind == null) rewind = new byte[REWIND_WINDOW];
        int room = Math.min(length, REWIND_WINDOW - rewindHeld);
        System.arraycopy(source, offset, rewind, rewindHeld, room);
        rewindHeld += room;
    }

    private void capture(byte value) {
        if (position != rewindHeld || rewindHeld >= REWIND_WINDOW) return;

        if (rewind == null) rewind = new byte[REWIND_WINDOW];
        rewind[rewindHeld++] = value;
    }

    @Override
    public long skip(long count) throws IOException {

        long skipped = 0;
        byte[] scratch = new byte[8192];

        while (skipped < count) {
            int wanted = (int) Math.min(scratch.length, count - skipped);
            int taken = read(scratch, 0, wanted);
            if (taken < 0) break;
            skipped += taken;
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        if (replaying()) return rewindHeld - rewindCursor;
        return delegate == null ? 0 : delegate.available();
    }

    @Override
    public List<AudioTrackInfoProvider> getTrackInfoProviders() {

        return Collections.emptyList();
    }

    public String truncation() {
        return delegate instanceof SabrInputStream sabr ? sabr.truncation() : null;
    }

    public boolean notAttested() {
        return delegate instanceof SabrInputStream sabr && sabr.notAttested();
    }

    @Override
    public void close() throws IOException {
        rewind = null;
        rewindHeld = 0;
        rewindCursor = -1;

        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
    }
}
