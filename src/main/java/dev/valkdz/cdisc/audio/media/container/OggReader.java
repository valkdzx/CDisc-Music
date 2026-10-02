package dev.valkdz.cdisc.audio.media.container;

import dev.valkdz.cdisc.audio.media.Codec;
import dev.valkdz.cdisc.audio.media.Demuxer;
import dev.valkdz.cdisc.audio.media.MediaInput;
import dev.valkdz.cdisc.audio.media.MediaReader;
import dev.valkdz.cdisc.audio.media.Packet;
import dev.valkdz.cdisc.audio.media.Tags;
import dev.valkdz.cdisc.audio.media.TrackFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class OggReader implements Demuxer {

    private static final int SYNC_SEARCH = 1 << 20;

    private final MediaReader in;
    private final ArrayDeque<Packet> queue = new ArrayDeque<>();
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private int serial;
    private boolean serialKnown;
    private TrackFormat format;
    private Tags tags = Tags.NONE;
    private int sampleRate;
    private long granuleOffset;
    private long lastGranule = -1;
    private long dataStart;
    private long durationMs = -1;
    private boolean ended;
    private boolean skipContinued;

    public OggReader(MediaInput input) throws IOException {
        this.in = new MediaReader(input, 64 * 1024);
        open();
    }

    private void open() throws IOException {
        List<byte[]> headers = new ArrayList<>();
        Packet first = rawNext();
        if (first == null) throw new IOException("The Ogg stream is empty");
        byte[] id = Arrays.copyOfRange(first.data(), first.offset(), first.offset() + first.length());

        if (startsWith(id, "OpusHead")) {
            int channels = id[9] & 0xFF;
            int preSkip = (id[10] & 0xFF) | ((id[11] & 0xFF) << 8);
            sampleRate = 48000;
            granuleOffset = preSkip;
            Packet comments = rawNext();
            if (comments != null && startsWithAt(comments, "OpusTags")) {
                tags = VorbisComments.parse(copy(comments), 8);
            } else if (comments != null) {
                queue.addFirst(comments);
            }
            format = TrackFormat.of(Codec.OPUS, 48000, channels, id).withSkip(preSkip, 80);
        } else if (id.length >= 7 && id[0] == 1 && startsWithAt(first, 1, "vorbis")) {
            int channels = id[11] & 0xFF;
            sampleRate = (int) ((id[12] & 0xFFL) | ((id[13] & 0xFFL) << 8) | ((id[14] & 0xFFL) << 16) | ((id[15] & 0xFFL) << 24));
            headers.add(id);
            for (int i = 0; i < 2; i++) {
                Packet header = rawNext();
                if (header == null) throw new IOException("The Vorbis headers are incomplete");
                byte[] bytes = copy(header);
                if (i == 0 && bytes.length > 7 && bytes[0] == 3) tags = VorbisComments.parse(bytes, 7);
                headers.add(bytes);
            }
            format = TrackFormat.of(Codec.VORBIS, sampleRate, channels, null).withHeaders(headers);
        } else if (id.length >= 13 + 38 && id[0] == 0x7F && startsWithAt(first, 1, "FLAC")) {
            byte[] streamInfo = Arrays.copyOfRange(id, 13 + 4, 13 + 4 + 34);
            sampleRate = ((streamInfo[10] & 0xFF) << 12) | ((streamInfo[11] & 0xFF) << 4) | ((streamInfo[12] & 0xF0) >> 4);
            int channels = ((streamInfo[12] & 0x0E) >> 1) + 1;
            int extra = ((id[7] & 0xFF) << 8) | (id[8] & 0xFF);
            for (int i = 0; i < extra; i++) {
                Packet header = rawNext();
                if (header == null) break;
                byte[] bytes = copy(header);
                if (bytes.length > 4 && (bytes[0] & 0x7F) == 4) tags = tags.orElse(VorbisComments.parse(bytes, 4));
            }
            format = TrackFormat.of(Codec.FLAC, sampleRate, channels, streamInfo);
        } else {
            throw new IOException("The Ogg stream holds no Opus, Vorbis or FLAC audio");
        }
        dataStart = in.position();
        findDuration();
    }

    private void findDuration() throws IOException {
        long length = in.length();
        if (!in.canSeek() || length <= 0) return;
        long here = in.position();
        try {
            long from = Math.max(dataStart, length - 128 * 1024);
            in.seek(from);
            long granule = -1;
            byte[] header = new byte[27];
            while (syncPage()) {
                in.peek(header, 27);
                long g = le64(header, 6);
                int s = le32(header, 14);
                if (s == serial && g >= 0) granule = g;
                in.skip(4);
            }
            if (granule > 0 && sampleRate > 0) durationMs = (granule - granuleOffset) * 1000 / sampleRate;
        } finally {
            in.seek(here);
            ended = false;
        }
    }

    private static boolean startsWith(byte[] data, String text) {
        if (data.length < text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (data[i] != text.charAt(i)) return false;
        return true;
    }

    private static boolean startsWithAt(Packet packet, String text) {
        return startsWithAt(packet, 0, text);
    }

    private static boolean startsWithAt(Packet packet, int at, String text) {
        if (packet.length() < at + text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (packet.data()[packet.offset() + at + i] != text.charAt(i)) return false;
        return true;
    }

    private static byte[] copy(Packet packet) {
        return Arrays.copyOfRange(packet.data(), packet.offset(), packet.offset() + packet.length());
    }

    private boolean syncPage() throws IOException {
        byte[] look = new byte[4];
        for (int scanned = 0; scanned < SYNC_SEARCH; scanned++) {
            if (in.peek(look, 4) < 4) return false;
            if (look[0] == 'O' && look[1] == 'g' && look[2] == 'g' && look[3] == 'S') return true;
            in.skip(1);
        }
        return false;
    }

    private Packet rawNext() throws IOException {
        while (queue.isEmpty()) {
            if (ended || !readPage()) return null;
        }
        return queue.poll();
    }

    private boolean readPage() throws IOException {
        if (!syncPage()) {
            ended = true;
            return false;
        }
        byte[] header = in.bytes(27);
        int type = header[5] & 0xFF;
        long granule = le64(header, 6);
        int pageSerial = le32(header, 14);
        int segments = header[26] & 0xFF;
        byte[] lacing = in.bytes(segments);
        int total = 0;
        for (byte b : lacing) total += b & 0xFF;
        byte[] body = in.bytes(total);

        if (!serialKnown) {
            serial = pageSerial;
            serialKnown = true;
        }
        if (pageSerial != serial) return true;
        boolean continued = (type & 0x01) != 0;
        if (!continued) partial.reset();
        boolean dropping = continued && skipContinued;
        skipContinued = false;

        long pageStartTime = lastGranule >= 0 && sampleRate > 0
                ? Math.max(0, lastGranule - granuleOffset) * 1_000_000L / sampleRate : Packet.UNKNOWN_TIME;
        boolean firstOnPage = true;
        int at = 0;
        for (int i = 0; i < segments; i++) {
            int size = lacing[i] & 0xFF;
            if (!dropping) partial.write(body, at, size);
            at += size;
            if (size < 255 && dropping) {
                dropping = false;
                partial.reset();
            } else if (size < 255) {
                byte[] packet = partial.toByteArray();
                partial.reset();
                queue.add(new Packet(packet, firstOnPage ? pageStartTime : Packet.UNKNOWN_TIME));
                firstOnPage = false;
            }
        }
        if (granule >= 0) lastGranule = granule;
        if ((type & 0x04) != 0) ended = true;
        return true;
    }

    private static long le64(byte[] b, int at) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[at + i] & 0xFF);
        return v;
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }

    @Override
    public TrackFormat format() {
        return format;
    }

    @Override
    public long durationMs() {
        return durationMs;
    }

    @Override
    public Tags tags() {
        return tags;
    }

    @Override
    public Packet next() throws IOException {
        return rawNext();
    }

    @Override
    public boolean canSeek() {
        return in.canSeek() && durationMs > 0;
    }

    @Override
    public long seek(long targetMs) throws IOException {
        long target = Math.max(0, targetMs) * sampleRate / 1000 + granuleOffset;
        long lo = dataStart;
        long hi = in.length();
        long best = dataStart;
        long bestGranule = 0;
        byte[] header = new byte[27];
        while (hi - lo > 8192) {
            long mid = (lo + hi) / 2;
            in.seek(mid);
            ended = false;
            if (!syncPage()) {
                hi = mid;
                continue;
            }
            long pageAt = in.position();
            in.peek(header, 27);
            long granule = le64(header, 6);
            if (granule < 0 || le32(header, 14) != serial) {
                lo = pageAt + 1;
                continue;
            }
            if (granule < target) {
                best = pageAt;
                bestGranule = granule;
                lo = pageAt + 1;
            } else {
                hi = mid;
            }
        }
        in.seek(best);
        queue.clear();
        partial.reset();
        ended = false;
        lastGranule = best == dataStart ? -1 : bestGranule;
        if (best != dataStart) {
            readPage();
            queue.clear();
            partial.reset();
            skipContinued = true;
        } else {
            lastGranule = granuleOffset;
        }
        return Math.max(0, (lastGranule - granuleOffset) * 1000 / sampleRate);
    }
}
