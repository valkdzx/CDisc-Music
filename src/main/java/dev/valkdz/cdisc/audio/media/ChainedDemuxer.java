package dev.valkdz.cdisc.audio.media;

import java.io.IOException;

// Plays self-contained segments back to back through one decoder, as a live stream sends them.
public final class ChainedDemuxer implements Demuxer {

    public interface Segments {
        Demuxer next() throws IOException;
    }

    private final Segments segments;
    private Demuxer current;
    private final TrackFormat format;

    public ChainedDemuxer(Segments segments) throws IOException {
        this.segments = segments;
        this.current = segments.next();
        if (current == null) throw new IOException("The stream has no segments");
        this.format = current.format();
    }

    @Override
    public TrackFormat format() {
        return format;
    }

    @Override
    public long durationMs() {
        return -1;
    }

    @Override
    public Packet next() throws IOException {
        while (current != null) {
            Packet packet = current.next();
            if (packet != null) return new Packet(packet.data(), packet.offset(), packet.length(), Packet.UNKNOWN_TIME);
            current.close();
            current = segments.next();
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        Demuxer open = current;
        current = null;
        if (open != null) open.close();
    }
}
