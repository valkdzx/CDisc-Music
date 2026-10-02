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

public final class FlacReader implements Demuxer {

    private static final int[] BLOCK_SIZES = {0, 192, 576, 1152, 2304, 4608, 0, 0, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768};

    private final MediaReader in;
    private final TrackFormat format;
    private Tags tags = Tags.NONE;
    private final int sampleRate;
    private final long totalSamples;
    private final int fixedBlock;
    private final long audioStart;
    private long[] seekSamples;
    private long[] seekOffsets;
    private final byte[] chunk = new byte[64 * 1024];

    public FlacReader(MediaInput input) throws IOException {
        this.in = new MediaReader(input, 128 * 1024);
        byte[] probe = new byte[10];
        while (in.peek(probe, 10) == 10 && Id3.startsTag(probe)) in.skip(Id3.tagLength(probe));
        byte[] magic = in.bytes(4);
        if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') throw new IOException("Not a FLAC stream");

        byte[] streamInfo = null;
        boolean last = false;
        while (!last) {
            int header = in.u8();
            last = (header & 0x80) != 0;
            int type = header & 0x7F;
            int length = in.u24();
            if (type == 0) {
                streamInfo = in.bytes(length);
            } else if (type == 3) {
                int points = length / 18;
                seekSamples = new long[points];
                seekOffsets = new long[points];
                for (int i = 0; i < points; i++) {
                    seekSamples[i] = in.u64();
                    seekOffsets[i] = in.u64();
                    in.u16();
                }
                in.skip(length - points * 18L);
            } else if (type == 4) {
                tags = tags.orElse(VorbisComments.parse(in.bytes(length), 0));
            } else {
                in.skip(length);
            }
        }
        if (streamInfo == null || streamInfo.length < 34) throw new IOException("The FLAC stream has no STREAMINFO");
        int minBlock = ((streamInfo[0] & 0xFF) << 8) | (streamInfo[1] & 0xFF);
        int maxBlock = ((streamInfo[2] & 0xFF) << 8) | (streamInfo[3] & 0xFF);
        fixedBlock = minBlock == maxBlock ? minBlock : 0;
        sampleRate = ((streamInfo[10] & 0xFF) << 12) | ((streamInfo[11] & 0xFF) << 4) | ((streamInfo[12] & 0xF0) >> 4);
        int channels = ((streamInfo[12] & 0x0E) >> 1) + 1;
        totalSamples = ((streamInfo[13] & 0x0FL) << 32) | ((streamInfo[14] & 0xFFL) << 24) | ((streamInfo[15] & 0xFFL) << 16)
                | ((streamInfo[16] & 0xFFL) << 8) | (streamInfo[17] & 0xFFL);
        format = TrackFormat.of(Codec.FLAC, sampleRate, channels, streamInfo);
        audioStart = in.position();
    }

    @Override
    public TrackFormat format() {
        return format;
    }

    @Override
    public long durationMs() {
        return totalSamples > 0 && sampleRate > 0 ? totalSamples * 1000 / sampleRate : -1;
    }

    @Override
    public Tags tags() {
        return tags;
    }

    static boolean frameHeaderAt(byte[] data, int at, int end) {
        if (at + 6 > end) return false;
        if ((data[at] & 0xFF) != 0xFF || (data[at + 1] & 0xFE) != 0xF8) return false;
        int b2 = data[at + 2] & 0xFF;
        int b3 = data[at + 3] & 0xFF;
        if ((b2 >> 4) == 0 || (b2 & 0x0F) == 15 || (b3 >> 4) >= 11 || ((b3 >> 1) & 7) == 3 || ((b3 >> 1) & 7) == 7 || (b3 & 1) != 0) {
            return false;
        }
        int length = headerLength(data, at, end);
        if (length < 0 || at + length + 1 > end) return false;
        return crc8(data, at, length) == (data[at + length] & 0xFF);
    }

    private static int headerLength(byte[] data, int at, int end) {
        int p = at + 4;
        int first = data[p] & 0xFF;
        int extra;
        if ((first & 0x80) == 0) extra = 0;
        else if ((first & 0xE0) == 0xC0) extra = 1;
        else if ((first & 0xF0) == 0xE0) extra = 2;
        else if ((first & 0xF8) == 0xF0) extra = 3;
        else if ((first & 0xFC) == 0xF8) extra = 4;
        else if ((first & 0xFE) == 0xFC) extra = 5;
        else if (first == 0xFE) extra = 6;
        else return -1;
        p += 1 + extra;
        int blockCode = (data[at + 2] & 0xFF) >> 4;
        int rateCode = data[at + 2] & 0x0F;
        if (blockCode == 6) p += 1;
        else if (blockCode == 7) p += 2;
        if (rateCode == 12) p += 1;
        else if (rateCode == 13 || rateCode == 14) p += 2;
        return p - at;
    }

    private static int crc8(byte[] data, int at, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            crc ^= data[at + i] & 0xFF;
            for (int b = 0; b < 8; b++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
        }
        return crc;
    }

    private long frameSample(byte[] data, int at) {
        int p = at + 4;
        int first = data[p] & 0xFF;
        int extra = (first & 0x80) == 0 ? 0 : (first & 0xE0) == 0xC0 ? 1 : (first & 0xF0) == 0xE0 ? 2
                : (first & 0xF8) == 0xF0 ? 3 : (first & 0xFC) == 0xF8 ? 4 : (first & 0xFE) == 0xFC ? 5 : 6;
        long value = extra == 0 ? first : first & (0x3F >> extra);
        for (int i = 1; i <= extra; i++) value = (value << 6) | (data[p + i] & 0x3F);
        boolean variable = (data[at + 1] & 1) != 0;
        if (variable) return value;
        int block = fixedBlock > 0 ? fixedBlock : BLOCK_SIZES[(data[at + 2] & 0xFF) >> 4];
        return value * block;
    }

    @Override
    public Packet next() throws IOException {
        int got = in.peek(chunk, 16);
        if (got < 6) return null;
        if (!frameHeaderAt(chunk, 0, got) && !resync()) return null;

        ByteArrayOutputStream frame = new ByteArrayOutputStream(8192);
        got = in.peek(chunk, chunk.length);
        long sample = frameSample(chunk, 0);
        int scanFrom = 2;
        while (true) {
            int end = -1;
            for (int i = scanFrom; i + 1 < got; i++) {
                if ((chunk[i] & 0xFF) == 0xFF && (chunk[i + 1] & 0xFE) == 0xF8 && frameHeaderAt(chunk, i, got)
                        && frameSample(chunk, i) > sample) {
                    end = i;
                    break;
                }
            }
            if (end > 0) {
                frame.write(chunk, 0, end);
                in.skip(end);
                break;
            }
            if (got < chunk.length) {
                frame.write(chunk, 0, got);
                in.skip(got);
                break;
            }
            int keep = 32;
            frame.write(chunk, 0, got - keep);
            in.skip(got - keep);
            got = in.peek(chunk, chunk.length);
            scanFrom = 0;
        }
        return new Packet(frame.toByteArray(), sampleRate > 0 ? sample * 1_000_000L / sampleRate : Packet.UNKNOWN_TIME);
    }

    private boolean resync() throws IOException {
        while (true) {
            int got = in.peek(chunk, chunk.length);
            if (got < 6) return false;
            for (int i = 0; i + 6 <= got; i++) {
                if (frameHeaderAt(chunk, i, got)) {
                    in.skip(i);
                    return true;
                }
            }
            in.skip(Math.max(1, got - 32));
        }
    }

    @Override
    public boolean canSeek() {
        return in.canSeek() && totalSamples > 0;
    }

    @Override
    public long seek(long targetMs) throws IOException {
        long target = Math.max(0, targetMs) * sampleRate / 1000;
        long offset = audioStart;
        if (seekSamples != null) {
            for (int i = 0; i < seekSamples.length; i++) {
                if (seekSamples[i] == -1L || seekSamples[i] > target) break;
                offset = audioStart + seekOffsets[i];
            }
        } else if (in.length() > audioStart && totalSamples > 0) {
            offset = audioStart + (long) ((double) target / totalSamples * (in.length() - audioStart) * 0.98);
        }
        in.seek(offset);
        if (!resync()) return targetMs;
        int got = in.peek(chunk, 32);
        return got >= 6 ? frameSample(chunk, 0) * 1000 / sampleRate : targetMs;
    }
}
