package dev.valkdz.cdisc.audio.media.container;

import dev.valkdz.cdisc.audio.media.Demuxer;
import dev.valkdz.cdisc.audio.media.MediaInput;
import dev.valkdz.cdisc.audio.media.MediaReader;
import dev.valkdz.cdisc.audio.media.Packet;
import dev.valkdz.cdisc.audio.media.Tags;
import dev.valkdz.cdisc.audio.media.TrackFormat;
import dev.valkdz.cdisc.audio.media.codec.PcmDecoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class WavReader implements Demuxer {

    private final MediaReader in;
    private TrackFormat format;
    private Tags tags = Tags.NONE;
    private long dataStart = -1;
    private long dataEnd;
    private int blockAlign;
    private int sampleRate;

    public WavReader(MediaInput input) throws IOException {
        this.in = new MediaReader(input);
        byte[] magic = in.bytes(12);
        String riff = new String(magic, 0, 4, StandardCharsets.US_ASCII);
        String kind = new String(magic, 8, 4, StandardCharsets.US_ASCII);
        if (riff.equals("FORM") && (kind.equals("AIFF") || kind.equals("AIFC"))) {
            readAiff(kind.equals("AIFC"));
        } else if ((riff.equals("RIFF") || riff.equals("RF64")) && kind.equals("WAVE")) {
            readWav();
        } else {
            throw new IOException("Not a WAV or AIFF file");
        }
        if (format == null || dataStart < 0) throw new IOException("The file has no audio data");
        in.seek(dataStart);
    }

    private void readWav() throws IOException {
        long length = in.length();
        while (!in.eof()) {
            String id = new String(in.bytes(4), StandardCharsets.US_ASCII);
            long size = in.u32le();
            long body = in.position();
            if (id.equals("fmt ")) {
                int tag = in.u16le();
                int channels = in.u16le();
                sampleRate = (int) in.u32le();
                in.u32le();
                blockAlign = in.u16le();
                int bits = in.u16le();
                if (tag == 0xFFFE && size >= 40) {
                    in.skip(8);
                    tag = in.u16le();
                }
                if (tag != 1 && tag != 3) throw new IOException("WAV format " + tag + " is not supported");
                int container = blockAlign > 0 && channels > 0 ? blockAlign / channels * 8 : bits;
                format = TrackFormat.pcm(sampleRate, channels, container, false, tag == 3);
            } else if (id.equals("data")) {
                dataStart = body;
                dataEnd = size == 0xFFFFFFFFL || size == 0 ? (length > 0 ? length : Long.MAX_VALUE) : body + size;
                if (length > 0) dataEnd = Math.min(dataEnd, length);
                if (!in.canSeek()) return;
            } else if (id.equals("LIST")) {
                readInfo(size);
            }
            long next = body + size + (size & 1);
            if (length > 0 && next >= length) break;
            in.seek(next);
        }
    }

    private void readInfo(long size) throws IOException {
        if (size < 4 || size > 1 << 20) return;
        byte[] body = in.bytes((int) size);
        if (!new String(body, 0, 4, StandardCharsets.US_ASCII).equals("INFO")) return;
        String title = null;
        String artist = null;
        int at = 4;
        while (at + 8 <= body.length) {
            String id = new String(body, at, 4, StandardCharsets.US_ASCII);
            int length = (body[at + 4] & 0xFF) | ((body[at + 5] & 0xFF) << 8) | ((body[at + 6] & 0xFF) << 16) | ((body[at + 7] & 0xFF) << 24);
            at += 8;
            if (length < 0 || at + length > body.length) break;
            String value = new String(body, at, length, StandardCharsets.UTF_8);
            if (id.equals("INAM")) title = value;
            if (id.equals("IART")) artist = value;
            at += length + (length & 1);
        }
        tags = tags.orElse(new Tags(Tags.clean(title), Tags.clean(artist)));
    }

    private void readAiff(boolean compressed) throws IOException {
        long length = in.length();
        while (!in.eof()) {
            String id = new String(in.bytes(4), StandardCharsets.US_ASCII);
            long size = in.u32();
            long body = in.position();
            if (id.equals("COMM")) {
                int channels = in.u16();
                in.u32();
                int bits = in.u16();
                sampleRate = (int) extended(in.bytes(10));
                boolean floating = false;
                boolean little = false;
                if (compressed && size >= 22) {
                    String kind = new String(in.bytes(4), StandardCharsets.US_ASCII);
                    if (kind.equals("fl32") || kind.equals("FL32")) {
                        floating = true;
                        bits = 32;
                    } else if (kind.equals("fl64") || kind.equals("FL64")) {
                        floating = true;
                        bits = 64;
                    } else if (kind.equals("sowt")) {
                        little = true;
                    } else if (!kind.equals("NONE") && !kind.equals("twos")) {
                        throw new IOException("AIFF-C compression " + kind + " is not supported");
                    }
                }
                int container = (bits + 7) / 8 * 8;
                blockAlign = container / 8 * channels;
                format = TrackFormat.pcm(sampleRate, channels, container, !little, floating);
            } else if (id.equals("SSND")) {
                long offset = in.u32();
                in.u32();
                dataStart = body + 8 + offset;
                dataEnd = body + size;
                if (!in.canSeek()) return;
            } else if (id.equals("NAME")) {
                tags = tags.orElse(new Tags(Tags.clean(new String(in.bytes((int) Math.min(size, 4096)), StandardCharsets.ISO_8859_1)), null));
            }
            long next = body + size + (size & 1);
            if (length > 0 && next >= length) break;
            in.seek(next);
        }
    }

    private static double extended(byte[] b) {
        int exponent = ((b[0] & 0x7F) << 8) | (b[1] & 0xFF);
        long mantissa = 0;
        for (int i = 2; i < 10; i++) mantissa = (mantissa << 8) | (b[i] & 0xFF);
        if (exponent == 0 && mantissa == 0) return 0;
        double value = (mantissa >>> 1) * Math.pow(2, exponent - 16383 - 62);
        return (b[0] & 0x80) != 0 ? -value : value;
    }

    @Override
    public TrackFormat format() {
        return format;
    }

    @Override
    public long durationMs() {
        if (dataEnd == Long.MAX_VALUE || blockAlign <= 0 || sampleRate <= 0) return -1;
        return (dataEnd - dataStart) / blockAlign * 1000 / sampleRate;
    }

    @Override
    public Tags tags() {
        return tags;
    }

    @Override
    public Packet next() throws IOException {
        long position = in.position();
        if (position >= dataEnd) return null;
        long frames = Math.min(PcmDecoder.MAX_FRAMES, (dataEnd - position) / blockAlign);
        if (frames <= 0) return null;
        byte[] data = new byte[(int) frames * blockAlign];
        int got = in.readAtMost(data, 0, data.length);
        if (got < blockAlign) return null;
        long frame = (position - dataStart) / blockAlign;
        return new Packet(data, 0, got - got % blockAlign, frame * 1_000_000L / sampleRate);
    }

    @Override
    public boolean canSeek() {
        return in.canSeek();
    }

    @Override
    public long seek(long targetMs) throws IOException {
        long frame = Math.max(0, targetMs) * sampleRate / 1000;
        in.seek(Math.min(dataEnd, dataStart + frame * blockAlign));
        return frame * 1000 / sampleRate;
    }
}
