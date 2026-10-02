package dev.valkdz.cdisc.audio.media.container;

import dev.valkdz.cdisc.audio.media.Codec;
import dev.valkdz.cdisc.audio.media.Demuxer;
import dev.valkdz.cdisc.audio.media.MediaInput;
import dev.valkdz.cdisc.audio.media.MediaReader;
import dev.valkdz.cdisc.audio.media.Packet;
import dev.valkdz.cdisc.audio.media.Tags;
import dev.valkdz.cdisc.audio.media.TrackFormat;
import dev.valkdz.cdisc.audio.media.codec.AacDecoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Mp4Reader implements Demuxer {

    private final MediaReader in;
    private final long length;

    private int trackId = -1;
    private long timescale;
    private long mediaDuration;
    private TrackFormat format;
    private Tags tags = Tags.NONE;
    private long editSkip;

    private long[] sampleOffsets;
    private int[] sampleSizes;
    private long[] sampleTimes;
    private int sampleCount;
    private int nextSample;

    private boolean fragmented;
    private int trexDuration;
    private int trexSize;
    private long[] segmentOffsets;
    private long[] segmentTimes;
    private long firstFragment = -1;
    private long segmentsEndMs = -1;

    private final List<long[]> pending = new ArrayList<>();
    private int pendingAt;
    private long fragmentTime;

    public Mp4Reader(MediaInput input) throws IOException {
        this.in = new MediaReader(input);
        this.length = input.length();
        open();
    }

    private void open() throws IOException {
        boolean haveMoov = false;
        while (!haveMoov || (fragmented && firstFragment < 0)) {
            long start = in.position();
            if (length > 0 && start + 8 > length) break;
            long[] box = header();
            if (box == null) break;
            long size = box[0];
            int type = (int) box[1];
            long end = size == 0 ? (length > 0 ? length : Long.MAX_VALUE) : start + size;

            if (type == fourcc("moov")) {
                parseContainer(end, "moov");
                haveMoov = true;
                if (!fragmented) break;
            } else if (type == fourcc("sidx")) {
                parseSidx(start, end);
            } else if (type == fourcc("moof")) {
                firstFragment = start;
                in.seek(start);
                break;
            } else if (type == fourcc("mdat") && !haveMoov && !in.canSeek()) {
                throw new IOException("The MP4 keeps its index after the audio and cannot be read as a stream");
            }
            if (end == Long.MAX_VALUE) break;
            if (in.position() != end) in.seek(end);
        }
        if (!haveMoov || format == null) throw new IOException("The MP4 holds no audio track we can play");
        if (!fragmented && sampleCount == 0) throw new IOException("The MP4 audio track is empty");
        if (!fragmented && sampleCount > 0) in.seek(sampleOffsets[0]);
    }

    private static int fourcc(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    private long[] header() throws IOException {
        if (in.eof()) return null;
        long size = in.u32();
        int type = in.s32();
        if (size == 1) size = in.u64();
        return new long[]{size, type};
    }

    private void parseContainer(long end, String parent) throws IOException {
        while (in.position() + 8 <= end) {
            long start = in.position();
            long[] box = header();
            if (box == null) return;
            long boxEnd = box[0] == 0 ? end : start + box[0];
            int type = (int) box[1];

            if (type == fourcc("trak")) {
                TrackState found = parseTrak(boxEnd);
                if (found != null && trackId < 0) adopt(found);
            } else if (type == fourcc("mvex")) {
                fragmented = true;
                parseMvex(boxEnd);
            } else if (type == fourcc("udta")) {
                parseUdta(boxEnd);
            } else if (type == fourcc("meta")) {
                in.skip(4);
                parseUdta(boxEnd);
            }
            in.seek(boxEnd);
        }
    }

    private static final class TrackState {
        int id;
        boolean audio;
        long timescale;
        long duration;
        TrackFormat format;
        long[] sttsCounts;
        long[] sttsDeltas;
        long[] stscFirst;
        long[] stscPerChunk;
        int[] sizes;
        int defaultSize;
        int sizeCount;
        long[] chunks;
        long editSkip;
    }

    private void adopt(TrackState track) throws IOException {
        trackId = track.id;
        timescale = track.timescale;
        mediaDuration = track.duration;
        editSkip = track.editSkip;
        TrackFormat f = track.format;
        if (editSkip > 0 && timescale > 0) {
            int skip = (int) Math.min(Integer.MAX_VALUE, editSkip * f.sampleRate() / timescale);
            f = f.withSkip(skip, f.seekPrerollMs());
        }
        format = f;
        if (track.sizes != null || track.defaultSize > 0) buildSamples(track);
    }

    private TrackState parseTrak(long end) throws IOException {
        TrackState track = new TrackState();
        walk(track, end);
        return track.audio && track.format != null ? track : null;
    }

    private void walk(TrackState track, long end) throws IOException {
        while (in.position() + 8 <= end) {
            long start = in.position();
            long[] box = header();
            if (box == null) return;
            long boxEnd = box[0] == 0 ? end : start + box[0];
            int type = (int) box[1];

            if (type == fourcc("mdia") || type == fourcc("minf") || type == fourcc("stbl") || type == fourcc("edts")) {
                walk(track, boxEnd);
            } else if (type == fourcc("tkhd")) {
                int version = in.u8();
                in.skip(3);
                in.skip(version == 1 ? 16 : 8);
                track.id = in.s32();
            } else if (type == fourcc("mdhd")) {
                int version = in.u8();
                in.skip(3);
                in.skip(version == 1 ? 16 : 8);
                track.timescale = in.u32();
                track.duration = version == 1 ? in.u64() : in.u32();
            } else if (type == fourcc("hdlr")) {
                in.skip(8);
                track.audio = in.s32() == fourcc("soun");
            } else if (type == fourcc("elst")) {
                int version = in.u8();
                in.skip(3);
                long entries = in.u32();
                for (long i = 0; i < entries; i++) {
                    long segmentDuration = version == 1 ? in.u64() : in.u32();
                    long mediaTime = version == 1 ? in.u64() : in.s32();
                    in.skip(4);
                    if (mediaTime >= 0) {
                        track.editSkip = mediaTime;
                        break;
                    }
                }
            } else if (type == fourcc("stsd")) {
                in.skip(8);
                track.format = parseSampleEntry(boxEnd);
            } else if (type == fourcc("stts")) {
                in.skip(4);
                int entries = (int) in.u32();
                track.sttsCounts = new long[entries];
                track.sttsDeltas = new long[entries];
                for (int i = 0; i < entries; i++) {
                    track.sttsCounts[i] = in.u32();
                    track.sttsDeltas[i] = in.u32();
                }
            } else if (type == fourcc("stsc")) {
                in.skip(4);
                int entries = (int) in.u32();
                track.stscFirst = new long[entries];
                track.stscPerChunk = new long[entries];
                for (int i = 0; i < entries; i++) {
                    track.stscFirst[i] = in.u32();
                    track.stscPerChunk[i] = in.u32();
                    in.skip(4);
                }
            } else if (type == fourcc("stsz")) {
                in.skip(4);
                track.defaultSize = in.s32();
                track.sizeCount = (int) in.u32();
                if (track.defaultSize == 0) {
                    track.sizes = new int[track.sizeCount];
                    for (int i = 0; i < track.sizeCount; i++) track.sizes[i] = in.s32();
                }
            } else if (type == fourcc("stz2")) {
                in.skip(7);
                int fieldSize = in.u8();
                track.sizeCount = (int) in.u32();
                track.sizes = new int[track.sizeCount];
                for (int i = 0; i < track.sizeCount; i++) {
                    if (fieldSize == 4) {
                        int both = in.u8();
                        track.sizes[i] = both >> 4;
                        if (++i < track.sizeCount) track.sizes[i] = both & 15;
                    } else {
                        track.sizes[i] = fieldSize == 8 ? in.u8() : in.u16();
                    }
                }
            } else if (type == fourcc("stco") || type == fourcc("co64")) {
                in.skip(4);
                int entries = (int) in.u32();
                track.chunks = new long[entries];
                for (int i = 0; i < entries; i++) track.chunks[i] = type == fourcc("co64") ? in.u64() : in.u32();
            }
            in.seek(boxEnd);
        }
    }

    private TrackFormat parseSampleEntry(long end) throws IOException {
        long start = in.position();
        long[] box = header();
        if (box == null) return null;
        long boxEnd = box[0] == 0 ? end : start + box[0];
        int type = (int) box[1];

        in.skip(6 + 2);
        int version = in.u16();
        in.skip(6);
        int channels = in.u16();
        in.skip(6);
        int sampleRate = (int) (in.u32() >>> 16);
        if (version == 1) in.skip(16);
        if (version == 2) {
            in.skip(4);
            sampleRate = (int) Double.longBitsToDouble(in.u64());
            channels = (int) in.u32();
            in.skip(20);
        }

        TrackFormat found = null;
        while (in.position() + 8 <= boxEnd) {
            long childStart = in.position();
            long[] child = header();
            if (child == null) break;
            long childEnd = child[0] == 0 ? boxEnd : childStart + child[0];
            int childType = (int) child[1];

            if (childType == fourcc("esds")) {
                found = parseEsds(childEnd, sampleRate, channels);
            } else if (childType == fourcc("dOps")) {
                byte[] body = in.bytes((int) (childEnd - in.position()));
                found = opusFromDops(body, channels);
            } else if (childType == fourcc("dfLa")) {
                in.skip(4);
                byte[] blocks = in.bytes((int) (childEnd - in.position()));
                found = flacFromBlocks(blocks, sampleRate, channels);
            }
            in.seek(childEnd);
        }
        if (found == null && type == fourcc(".mp3")) found = TrackFormat.of(Codec.MP3, sampleRate, channels, null);
        if (found == null && (type == fourcc("ulaw") || type == fourcc("alaw"))) return null;
        if (found == null && type == fourcc("Opus")) return null;
        return found;
    }

    private TrackFormat parseEsds(long end, int sampleRate, int channels) throws IOException {
        in.skip(4);
        int objectType = -1;
        byte[] asc = null;
        while (in.position() + 2 <= end) {
            int tag = in.u8();
            int size = 0;
            for (int i = 0; i < 4; i++) {
                int b = in.u8();
                size = (size << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) break;
            }
            if (tag == 0x03) {
                in.skip(2);
                int flags = in.u8();
                if ((flags & 0x80) != 0) in.skip(2);
                if ((flags & 0x40) != 0) in.skip(in.u8());
                if ((flags & 0x20) != 0) in.skip(2);
            } else if (tag == 0x04) {
                objectType = in.u8();
                in.skip(12);
            } else if (tag == 0x05) {
                asc = in.bytes(size);
            } else {
                in.skip(size);
            }
        }
        if (objectType == 0x6B || objectType == 0x69) return TrackFormat.of(Codec.MP3, sampleRate, channels, null);
        if (objectType == 0xDD) return null;
        if (asc == null) asc = AacDecoder.configFor(2, nearestIndex(sampleRate), Math.min(channels, 7));
        AacDecoder.Config config = AacDecoder.parseConfig(asc);
        return TrackFormat.of(Codec.AAC, config.sampleRate(), config.outputChannels(), asc);
    }

    private static int nearestIndex(int rate) {
        int best = 0;
        for (int i = 1; i < AacDecoder.SAMPLE_RATES.length; i++) {
            if (Math.abs(AacDecoder.SAMPLE_RATES[i] - rate) < Math.abs(AacDecoder.SAMPLE_RATES[best] - rate)) best = i;
        }
        return best;
    }

    static TrackFormat opusFromDops(byte[] dops, int channels) {
        if (dops.length < 11) return null;
        byte[] head = new byte[19 + Math.max(0, dops.length - 11)];
        System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, head, 0, 8);
        head[8] = 1;
        head[9] = dops[1];
        int preSkip = ((dops[2] & 0xFF) << 8) | (dops[3] & 0xFF);
        head[10] = (byte) preSkip;
        head[11] = (byte) (preSkip >> 8);
        long rate = ((long) (dops[4] & 0xFF) << 24) | ((dops[5] & 0xFF) << 16) | ((dops[6] & 0xFF) << 8) | (dops[7] & 0xFF);
        head[12] = (byte) rate;
        head[13] = (byte) (rate >> 8);
        head[14] = (byte) (rate >> 16);
        head[15] = (byte) (rate >> 24);
        head[16] = dops[9];
        head[17] = dops[8];
        head[18] = dops[10];
        System.arraycopy(dops, 11, head, 19, dops.length - 11);
        return TrackFormat.of(Codec.OPUS, 48000, dops[1] & 0xFF, head).withSkip(preSkip, 80);
    }

    static TrackFormat flacFromBlocks(byte[] blocks, int sampleRate, int channels) {
        if (blocks.length < 4 + 34) return null;
        byte[] streamInfo = Arrays.copyOfRange(blocks, 4, 4 + 34);
        int rate = ((streamInfo[10] & 0xFF) << 12) | ((streamInfo[11] & 0xFF) << 4) | ((streamInfo[12] & 0xF0) >> 4);
        int ch = ((streamInfo[12] & 0x0E) >> 1) + 1;
        return TrackFormat.of(Codec.FLAC, rate > 0 ? rate : sampleRate, ch, streamInfo);
    }

    private void parseMvex(long end) throws IOException {
        while (in.position() + 8 <= end) {
            long start = in.position();
            long[] box = header();
            if (box == null) return;
            long boxEnd = start + box[0];
            if ((int) box[1] == fourcc("trex")) {
                in.skip(4);
                int id = in.s32();
                in.skip(4);
                int duration = in.s32();
                int size = in.s32();
                if (trackId < 0 || id == trackId) {
                    trexDuration = duration;
                    trexSize = size;
                }
            }
            in.seek(boxEnd);
        }
    }

    private void parseUdta(long end) throws IOException {
        while (in.position() + 8 <= end) {
            long start = in.position();
            long[] box = header();
            if (box == null) return;
            long boxEnd = box[0] == 0 ? end : start + box[0];
            int type = (int) box[1];
            if (type == fourcc("meta")) {
                in.skip(4);
                parseUdta(boxEnd);
            } else if (type == fourcc("ilst")) {
                parseIlst(boxEnd);
            }
            in.seek(boxEnd);
        }
    }

    private void parseIlst(long end) throws IOException {
        String title = null;
        String artist = null;
        while (in.position() + 8 <= end) {
            long start = in.position();
            long[] box = header();
            if (box == null) break;
            long boxEnd = start + box[0];
            int type = (int) box[1];
            boolean wantTitle = type == (0xA9 << 24 | fourcc("\0nam"));
            boolean wantArtist = type == (0xA9 << 24 | fourcc("\0ART"));
            if ((wantTitle || wantArtist) && in.position() + 16 <= boxEnd) {
                long dataStart = in.position();
                long[] data = header();
                if (data != null && (int) data[1] == fourcc("data")) {
                    in.skip(8);
                    int textLength = (int) (dataStart + data[0] - in.position());
                    if (textLength > 0 && textLength < 4096) {
                        String text = new String(in.bytes(textLength), StandardCharsets.UTF_8);
                        if (wantTitle) title = text;
                        else artist = text;
                    }
                }
            }
            in.seek(boxEnd);
        }
        tags = tags.orElse(new Tags(Tags.clean(title), Tags.clean(artist)));
    }

    private void parseSidx(long start, long end) throws IOException {
        int version = in.u8();
        in.skip(3);
        in.skip(4);
        long scale = in.u32();
        long earliest = version == 0 ? in.u32() : in.u64();
        long firstOffset = version == 0 ? in.u32() : in.u64();
        in.skip(2);
        int count = in.u16();
        segmentOffsets = new long[count];
        segmentTimes = new long[count];
        long offset = end + firstOffset;
        long time = earliest;
        for (int i = 0; i < count; i++) {
            long reference = in.u32();
            long duration = in.u32();
            in.skip(4);
            segmentOffsets[i] = offset;
            segmentTimes[i] = scale > 0 ? time * 1000 / scale : 0;
            offset += reference & 0x7FFFFFFFL;
            time += duration;
        }
        if (scale > 0) segmentsEndMs = time * 1000 / scale;
    }

    private void buildSamples(TrackState t) {
        int count = t.sizes != null ? t.sizes.length : t.sizeCount;
        if (t.chunks == null || t.stscFirst == null || count == 0) return;
        sampleOffsets = new long[count];
        sampleSizes = new int[count];
        sampleTimes = new long[count];

        int sample = 0;
        for (int entry = 0; entry < t.stscFirst.length && sample < count; entry++) {
            long firstChunk = t.stscFirst[entry] - 1;
            long lastChunk = entry + 1 < t.stscFirst.length ? t.stscFirst[entry + 1] - 1 : t.chunks.length;
            for (long chunk = firstChunk; chunk < lastChunk && chunk < t.chunks.length && sample < count; chunk++) {
                long offset = t.chunks[(int) chunk];
                for (long i = 0; i < t.stscPerChunk[entry] && sample < count; i++) {
                    int size = t.sizes != null ? t.sizes[sample] : t.defaultSize;
                    sampleOffsets[sample] = offset;
                    sampleSizes[sample] = size;
                    offset += size;
                    sample++;
                }
            }
        }
        sampleCount = sample;

        long time = 0;
        int at = 0;
        if (t.sttsCounts != null) {
            for (int e = 0; e < t.sttsCounts.length && at < sampleCount; e++) {
                for (long i = 0; i < t.sttsCounts[e] && at < sampleCount; i++) {
                    sampleTimes[at++] = time;
                    time += t.sttsDeltas[e];
                }
            }
        }
        while (at < sampleCount) sampleTimes[at++] = time;
    }

    @Override
    public TrackFormat format() {
        return format;
    }

    @Override
    public long durationMs() {
        if (timescale <= 0) return -1;
        if (mediaDuration > 0 && mediaDuration != 0xFFFFFFFFL) return Math.max(0, mediaDuration - editSkip) * 1000 / timescale;
        if (sampleCount > 0) return Math.max(0, sampleTimes[sampleCount - 1] - editSkip) * 1000 / timescale;
        return segmentsEndMs;
    }

    @Override
    public Tags tags() {
        return tags;
    }

    @Override
    public Packet next() throws IOException {
        if (!fragmented) {
            if (nextSample >= sampleCount) return null;
            int i = nextSample++;
            in.seek(sampleOffsets[i]);
            byte[] data = new byte[sampleSizes[i]];
            if (in.readAtMost(data, 0, data.length) < data.length) return null;
            return new Packet(data, usOf(sampleTimes[i]));
        }

        while (pendingAt >= pending.size()) {
            pending.clear();
            pendingAt = 0;
            if (!nextFragment()) return null;
        }
        long[] sample = pending.get(pendingAt++);
        if (sample[0] > in.position()) in.seek(sample[0]);
        else if (sample[0] < in.position()) {
            if (!in.canSeek()) throw new IOException("MP4 fragment samples run backwards");
            in.seek(sample[0]);
        }
        byte[] data = new byte[(int) sample[1]];
        if (in.readAtMost(data, 0, data.length) < data.length) return null;
        return new Packet(data, usOf(sample[2]));
    }

    private long usOf(long time) {
        return timescale > 0 ? (time - editSkip) * 1_000_000L / timescale : Packet.UNKNOWN_TIME;
    }

    private boolean nextFragment() throws IOException {
        while (true) {
            long start = in.position();
            if (length > 0 && start + 8 > length) return false;
            long[] box = header();
            if (box == null) return false;
            long size = box[0];
            int type = (int) box[1];
            if (size == 0) {
                if (type == fourcc("mdat")) continue;
                return false;
            }
            long end = start + size;
            if (type == fourcc("moof")) {
                parseMoof(start, end);
                in.seek(end);
                if (!pending.isEmpty()) return true;
            } else if (type == fourcc("mdat") && !pending.isEmpty()) {
                return true;
            } else {
                in.seek(end);
            }
        }
    }

    private void parseMoof(long moofStart, long end) throws IOException {
        while (in.position() + 8 <= end) {
            long start = in.position();
            long[] box = header();
            if (box == null) return;
            long boxEnd = start + box[0];
            if ((int) box[1] == fourcc("traf")) parseTraf(moofStart, boxEnd);
            in.seek(boxEnd);
        }
    }

    private void parseTraf(long moofStart, long end) throws IOException {
        int id = -1;
        long base = moofStart;
        int defaultDuration = trexDuration;
        int defaultSize = trexSize;
        long dataEnd = moofStart;
        while (in.position() + 8 <= end) {
            long start = in.position();
            long[] box = header();
            if (box == null) return;
            long boxEnd = start + box[0];
            int type = (int) box[1];
            if (type == fourcc("tfhd")) {
                in.u8();
                int flags = in.u24();
                id = in.s32();
                if ((flags & 0x01) != 0) base = in.u64();
                if ((flags & 0x02) != 0) in.skip(4);
                if ((flags & 0x08) != 0) defaultDuration = in.s32();
                if ((flags & 0x10) != 0) defaultSize = in.s32();
            } else if (type == fourcc("tfdt")) {
                int version = in.u8();
                in.skip(3);
                fragmentTime = version == 1 ? in.u64() : in.u32();
            } else if (type == fourcc("trun") && (trackId < 0 || id == trackId)) {
                in.u8();
                int flags = in.u24();
                int count = (int) in.u32();
                long offset = (flags & 0x01) != 0 ? base + in.s32() : dataEnd;
                if ((flags & 0x04) != 0) in.skip(4);
                long time = fragmentTime;
                for (int i = 0; i < count; i++) {
                    long duration = (flags & 0x100) != 0 ? in.u32() : defaultDuration;
                    long size = (flags & 0x200) != 0 ? in.u32() : defaultSize;
                    if ((flags & 0x400) != 0) in.skip(4);
                    if ((flags & 0x800) != 0) in.skip(4);
                    pending.add(new long[]{offset, size, time});
                    offset += size;
                    time += duration;
                }
                dataEnd = offset;
                fragmentTime = time;
            }
            in.seek(boxEnd);
        }
    }

    @Override
    public boolean canSeek() {
        return in.canSeek();
    }

    @Override
    public long seek(long targetMs) throws IOException {
        long target = Math.max(0, targetMs) * timescale / 1000 + editSkip;
        if (!fragmented) {
            int lo = 0;
            int hi = sampleCount - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (sampleTimes[mid] <= target) lo = mid;
                else hi = mid - 1;
            }
            nextSample = Math.max(0, lo);
            return sampleCount == 0 ? 0 : Math.max(0, usOf(sampleTimes[nextSample]) / 1000);
        }

        pending.clear();
        pendingAt = 0;
        if (segmentOffsets != null && segmentOffsets.length > 0) {
            int i = 0;
            while (i + 1 < segmentTimes.length && segmentTimes[i + 1] <= targetMs) i++;
            in.seek(segmentOffsets[i]);
        } else {
            in.seek(firstFragment >= 0 ? firstFragment : 0);
            while (true) {
                long start = in.position();
                if (!nextFragment()) {
                    in.seek(start);
                    break;
                }
                long[] last = pending.get(pending.size() - 1);
                if (last[2] >= target) {
                    pending.clear();
                    in.seek(start);
                    break;
                }
                pending.clear();
                pendingAt = 0;
            }
        }
        return targetMs;
    }
}
