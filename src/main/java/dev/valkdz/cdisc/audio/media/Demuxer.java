package dev.valkdz.cdisc.audio.media;

import java.io.Closeable;
import java.io.IOException;

public interface Demuxer extends Closeable {

    TrackFormat format();

    long durationMs();

    Packet next() throws IOException;

    default boolean canSeek() {
        return false;
    }

    // Lands on a packet at or before the target and returns its time.
    default long seek(long targetMs) throws IOException {
        throw new IOException("This stream cannot seek");
    }

    default Tags tags() {
        return Tags.NONE;
    }

    @Override
    default void close() throws IOException {
    }
}
