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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MatroskaReader implements Demuxer {

    private static final long UNKNOWN = -1;

    private static final int EBML = 0x1A45DFA3;
    private static final int SEGMENT = 0x18538067;
    private static final int SEEK_HEAD = 0x114D9B74;
    private static final int SEEK = 0x4DBB;
    private static final int SEEK_ID = 0x53AB;
    private static final int SEEK_POSITION = 0x53AC;
    private static final int INFO = 0x1549A966;
    private static final int TIMECODE_SCALE = 0x2AD7B1;
    private static final int DURATION = 0x4489;
    private static final int TITLE = 0x7BA9;
    private static final int TRACKS = 0x1654AE6B;
    private static final int TRACK_ENTRY = 0xAE;
    private static final int TRACK_NUMBER = 0xD7;
    private static final int TRACK_TYPE = 0x83;
    private static final int CODEC_ID = 0x86;
    private static final int CODEC_PRIVATE = 0x63A2;
    private static final int CODEC_DELAY = 0x56AA;
    private static final int AUDIO = 0xE1;
    private static final int SAMPLING_FREQUENCY = 0xB5;
    private static final int CHANNELS = 0x9F;
    private static final int BIT_DEPTH = 0x6264;
    private static final int CUES = 0x1C53BB6B;
    private static final int CUE_POINT = 0xBB;
    private static final int CUE_TIME = 0xB3;
    private static final int CUE_TRACK_POSITIONS = 0xB7;
    private static final int CUE_TRACK = 0xF7;
    private static final int CUE_CLUSTER_POSITION = 0xF1;
    private static final int CLUSTER = 0x1F43B675;
    private static final int CLUSTER_TIMECODE = 0xE7;
    private static final int SIMPLE_BLOCK = 0xA3;
    private static final int BLOCK_GROUP = 0xA0;
    private static final int BLOCK = 0xA1;
    private static final int TAGS = 0x1254C367;
    private static final int TAG = 0x7373;
    private static final int SIMPLE_TAG = 0x67C8;
    private static final int TAG_NAME = 0x45A3;
    private static final int TAG_STRING = 0x4487;

    private final MediaReader in;
    private final ArrayDeque<Packet> queue = new ArrayDeque<>();
    private long segmentStart;
    private long segmentEnd = Long.MAX_VALUE;
    private long timecodeScale = 1_000_000;
    private double duration = -1;
    private long trackNumber = -1;
    private long codecDelayNs;
    private TrackFormat format;
    private Tags tags = Tags.NONE;
    private String title;
    private long clusterTime;
    private long firstCluster = -1;
    private long cuesPosition = -1;
    private long[] cueTimes;
    private long[] cuePositions;

    public MatroskaReader(MediaInput input) throws IOException {
        this.in = new MediaReader(input, 64 * 1024);
        open();
    }

    private long id() throws IOException {
        int first = in.u8();
        if (first == 0) throw new IOException("Bad EBML element id");
        int length = Integer.numberOfLeadingZeros(first) - 23;
        long value = first;
        for (int i = 1; i < length; i++) value = (value << 8) | in.u8();
        return value;
    }

    private long size() throws IOException {
        int first = in.u8();
        if (first == 0) throw new IOException("Bad EBML size");
        int length = Integer.numberOfLeadingZeros(first) - 23;
        long value = first & (0xFF >> length);
        boolean allOnes = value == (0xFF >> length);
        for (int i = 1; i < length; i++) {
            int b = in.u8();
            if (b != 0xFF) allOnes = false;
            value = (value << 8) | b;
        }
        return allOnes ? UNKNOWN : value;
    }

    private long uint(long size) throws IOException {
        long value = 0;
        for (long i = 0; i < size; i++) value = (value << 8) | in.u8();
        return value;
    }

    private double floating(long size) throws IOException {
        if (size == 4) return Float.intBitsToFloat((int) uint(4));
        if (size == 8) return Double.longBitsToDouble(uint(8));
        in.skip(size);
        return 0;
    }

    private String string(long size) throws IOException {
        byte[] bytes = in.bytes((int) Math.min(size, 1 << 20));
        if (size > bytes.length) in.skip(size - bytes.length);
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private void open() throws IOException {
        if (id() != EBML) throw new IOException("Not a Matroska or WebM stream");
        in.skip(size());
        while (true) {
            long id = id();
            long size = size();
            if (id == SEGMENT) {
                segmentStart = in.position();
                if (size != UNKNOWN) segmentEnd = segmentStart + size;
                break;
            }
            if (size == UNKNOWN) throw new IOException("The Matroska stream has no segment");
            in.skip(size);
        }

        while (firstCluster < 0) {
            if (in.position() >= segmentEnd || in.eof()) break;
            long start = in.position();
            long id = id();
            long size = size();
            long end = size == UNKNOWN ? UNKNOWN : in.position() + size;
            if (id == CLUSTER) {
                firstCluster = start;
                in.seek(start);
                break;
            }
            if (size == UNKNOWN) throw new IOException("A Matroska element of unknown size before the first cluster");
            if (id == INFO) readInfo(end);
            else if (id == TRACKS) readTracks(end);
            else if (id == SEEK_HEAD) readSeekHead(end);
            else if (id == CUES) readCues(end);
            else if (id == TAGS) readTags(end);
            in.seek(end);
        }
        if (format == null) throw new IOException("The Matroska stream holds no audio track we can play");
        if (title != null) tags = tags.orElse(new Tags(Tags.clean(title), null));

        if (cueTimes == null && cuesPosition > 0 && in.canSeek()) {
            long here = in.position();
            try {
                in.seek(cuesPosition);
                if (id() == CUES) {
                    long size = size();
                    if (size != UNKNOWN) readCues(in.position() + size);
                }
            } catch (IOException ignored) {
            } finally {
                in.seek(here);
            }
        }
    }

    private void readInfo(long end) throws IOException {
        while (in.position() < end) {
            long id = id();
            long size = size();
            if (id == TIMECODE_SCALE) timecodeScale = uint(size);
            else if (id == DURATION) duration = floating(size);
            else if (id == TITLE) title = string(size);
            else in.skip(size);
        }
    }

    private void readSeekHead(long end) throws IOException {
        while (in.position() < end) {
            long id = id();
            long size = size();
            if (id != SEEK) {
                in.skip(size);
                continue;
            }
            long seekEnd = in.position() + size;
            long target = -1;
            long position = -1;
            while (in.position() < seekEnd) {
                long child = id();
                long childSize = size();
                if (child == SEEK_ID) target = uint(childSize);
                else if (child == SEEK_POSITION) position = uint(childSize);
                else in.skip(childSize);
            }
            if (target == CUES && position >= 0) cuesPosition = segmentStart + position;
        }
    }

    private void readCues(long end) throws IOException {
        List<long[]> points = new ArrayList<>();
        while (in.position() < end) {
            long id = id();
            long size = size();
            if (id != CUE_POINT) {
                in.skip(size);
                continue;
            }
            long pointEnd = in.position() + size;
            long time = -1;
            long position = -1;
            while (in.position() < pointEnd) {
                long child = id();
                long childSize = size();
                if (child == CUE_TIME) {
                    time = uint(childSize);
                } else if (child == CUE_TRACK_POSITIONS && position < 0) {
                    long positionsEnd = in.position() + childSize;
                    long track = -1;
                    long cluster = -1;
                    while (in.position() < positionsEnd) {
                        long grand = id();
                        long grandSize = size();
                        if (grand == CUE_TRACK) track = uint(grandSize);
                        else if (grand == CUE_CLUSTER_POSITION) cluster = uint(grandSize);
                        else in.skip(grandSize);
                    }
                    if (trackNumber < 0 || track == trackNumber) position = cluster;
                } else {
                    in.skip(childSize);
                }
            }
            if (time >= 0 && position >= 0) points.add(new long[]{time, segmentStart + position});
        }
        cueTimes = new long[points.size()];
        cuePositions = new long[points.size()];
        for (int i = 0; i < points.size(); i++) {
            cueTimes[i] = points.get(i)[0];
            cuePositions[i] = points.get(i)[1];
        }
    }

    private void readTags(long end) throws IOException {
        String tagTitle = null;
        String artist = null;
        while (in.position() < end) {
            long id = id();
            long size = size();
            if (id == TAG) continue;
            if (id == SIMPLE_TAG) {
                long tagEnd = in.position() + size;
                String name = null;
                String value = null;
                while (in.position() < tagEnd) {
                    long child = id();
                    long childSize = size();
                    if (child == TAG_NAME) name = string(childSize);
                    else if (child == TAG_STRING) value = string(childSize);
                    else in.skip(childSize);
                }
                if ("TITLE".equalsIgnoreCase(name) && tagTitle == null) tagTitle = value;
                if ("ARTIST".equalsIgnoreCase(name) && artist == null) artist = value;
                continue;
            }
            in.skip(size);
        }
        tags = tags.orElse(new Tags(Tags.clean(tagTitle), Tags.clean(artist)));
    }

    private void readTracks(long end) throws IOException {
        while (in.position() < end) {
            long id = id();
            long size = size();
            if (id != TRACK_ENTRY) {
                in.skip(size);
                continue;
            }
            long entryEnd = in.position() + size;
            long number = -1;
            long type = -1;
            String codec = null;
            byte[] codecPrivate = null;
            long delay = 0;
            double rate = 8000;
            int channels = 1;
            int bitDepth = 0;
            while (in.position() < entryEnd) {
                long child = id();
                long childSize = size();
                if (child == TRACK_NUMBER) number = uint(childSize);
                else if (child == TRACK_TYPE) type = uint(childSize);
                else if (child == CODEC_ID) codec = string(childSize);
                else if (child == CODEC_PRIVATE) codecPrivate = in.bytes((int) childSize);
                else if (child == CODEC_DELAY) delay = uint(childSize);
                else if (child == AUDIO) {
                    long audioEnd = in.position() + childSize;
                    while (in.position() < audioEnd) {
                        long grand = id();
                        long grandSize = size();
                        if (grand == SAMPLING_FREQUENCY) rate = floating(grandSize);
                        else if (grand == CHANNELS) channels = (int) uint(grandSize);
                        else if (grand == BIT_DEPTH) bitDepth = (int) uint(grandSize);
                        else in.skip(grandSize);
                    }
                } else {
                    in.skip(childSize);
                }
            }
            if (type != 2 || format != null || codec == null) continue;
            TrackFormat found = formatOf(codec, codecPrivate, (int) Math.round(rate), channels, bitDepth);
            if (found != null) {
                if (delay > 0 && found.codec() != Codec.OPUS) {
                    found = found.withSkip((int) ((delay * found.sampleRate() + 500_000_000L) / 1_000_000_000L), found.seekPrerollMs());
                }
                format = found;
                trackNumber = number;
                codecDelayNs = delay;
            }
        }
    }

    private static TrackFormat formatOf(String codec, byte[] priv, int rate, int channels, int bitDepth) throws IOException {
        if (codec.equals("A_OPUS")) {
            if (priv == null || priv.length < 19) return null;
            int preSkip = (priv[10] & 0xFF) | ((priv[11] & 0xFF) << 8);
            return TrackFormat.of(Codec.OPUS, 48000, priv[9] & 0xFF, priv).withSkip(preSkip, 80);
        }
        if (codec.equals("A_VORBIS")) {
            List<byte[]> headers = xiph(priv);
            return headers == null ? null : TrackFormat.of(Codec.VORBIS, rate, channels, null).withHeaders(headers);
        }
        if (codec.startsWith("A_AAC")) {
            byte[] asc = priv;
            if (asc == null || asc.length < 2) {
                int profile = codec.contains("/MAIN") ? 1 : 2;
                int index = 4;
                for (int i = 0; i < AacDecoder.SAMPLE_RATES.length; i++) {
                    if (AacDecoder.SAMPLE_RATES[i] == rate) index = i;
                }
                asc = AacDecoder.configFor(profile, index, channels);
            }
            AacDecoder.Config config = AacDecoder.parseConfig(asc);
            return TrackFormat.of(Codec.AAC, config.sampleRate(), config.outputChannels(), asc);
        }
        if (codec.equals("A_MPEG/L3") || codec.equals("A_MPEG/L2") || codec.equals("A_MPEG/L1")) {
            return TrackFormat.of(Codec.MP3, rate, channels, null);
        }
        if (codec.equals("A_FLAC")) {
            if (priv == null || priv.length < 8 + 34) return null;
            return Mp4Reader.flacFromBlocks(Arrays.copyOfRange(priv, 4, priv.length), rate, channels);
        }
        if (codec.equals("A_PCM/INT/LIT") || codec.equals("A_PCM/INT/BIG")) {
            return TrackFormat.pcm(rate, channels, bitDepth > 0 ? bitDepth : 16, codec.endsWith("BIG"), false);
        }
        if (codec.equals("A_PCM/FLOAT/IEEE")) {
            return TrackFormat.pcm(rate, channels, bitDepth > 0 ? bitDepth : 32, false, true);
        }
        return null;
    }

    private static List<byte[]> xiph(byte[] priv) {
        if (priv == null || priv.length < 3) return null;
        int count = (priv[0] & 0xFF) + 1;
        int at = 1;
        int[] sizes = new int[count];
        int total = 0;
        for (int i = 0; i < count - 1; i++) {
            int size = 0;
            int b;
            do {
                if (at >= priv.length) return null;
                b = priv[at++] & 0xFF;
                size += b;
            } while (b == 255);
            sizes[i] = size;
            total += size;
        }
        sizes[count - 1] = priv.length - at - total;
        if (sizes[count - 1] < 0) return null;
        List<byte[]> out = new ArrayList<>();
        for (int size : sizes) {
            out.add(Arrays.copyOfRange(priv, at, at + size));
            at += size;
        }
        return out;
    }

    @Override
    public TrackFormat format() {
        return format;
    }

    @Override
    public long durationMs() {
        return duration > 0 ? (long) (duration * timecodeScale / 1_000_000) : -1;
    }

    @Override
    public Tags tags() {
        return tags;
    }

    @Override
    public Packet next() throws IOException {
        while (queue.isEmpty()) {
            if (in.position() >= segmentEnd || in.eof()) return null;
            long id;
            long size;
            try {
                id = id();
                size = size();
            } catch (IOException e) {
                return null;
            }
            if (id == CLUSTER || id == SEGMENT || id == BLOCK_GROUP) continue;
            if (id == CLUSTER_TIMECODE) {
                clusterTime = uint(size);
            } else if (id == SIMPLE_BLOCK || id == BLOCK) {
                readBlock(size);
            } else {
                if (size == UNKNOWN) return null;
                in.skip(size);
            }
        }
        return queue.poll();
    }

    private void readBlock(long size) throws IOException {
        long end = in.position() + size;
        long track = blockTrack();
        if (track != trackNumber) {
            in.seek(end);
            return;
        }
        int relative = (short) in.u16();
        int flags = in.u8();
        long time = (clusterTime + relative) * timecodeScale / 1000 - codecDelayNs / 1000;
        int lacing = (flags >> 1) & 3;
        if (lacing == 0) {
            queue.add(new Packet(in.bytes((int) (end - in.position())), time));
            return;
        }
        int frames = in.u8() + 1;
        int[] sizes = new int[frames];
        long remaining;
        if (lacing == 1) {
            for (int i = 0; i < frames - 1; i++) {
                int s = 0;
                int b;
                do {
                    b = in.u8();
                    s += b;
                } while (b == 255);
                sizes[i] = s;
            }
        } else if (lacing == 3) {
            sizes[0] = (int) vint();
            for (int i = 1; i < frames - 1; i++) {
                long start = in.position();
                long raw = vint();
                int length = (int) (in.position() - start);
                long bias = (1L << (7 * length - 1)) - 1;
                sizes[i] = (int) (sizes[i - 1] + raw - bias);
            }
        }
        remaining = end - in.position();
        if (lacing == 2) {
            for (int i = 0; i < frames; i++) sizes[i] = (int) (remaining / frames);
        } else {
            long used = 0;
            for (int i = 0; i < frames - 1; i++) used += sizes[i];
            sizes[frames - 1] = (int) (remaining - used);
        }
        for (int i = 0; i < frames; i++) {
            if (sizes[i] < 0) throw new IOException("Bad Matroska lacing");
            queue.add(new Packet(in.bytes(sizes[i]), i == 0 ? time : Packet.UNKNOWN_TIME));
        }
    }

    private long blockTrack() throws IOException {
        return vint();
    }

    private long vint() throws IOException {
        int first = in.u8();
        int length = Integer.numberOfLeadingZeros(first) - 23;
        long value = first & (0xFF >> length);
        for (int i = 1; i < length; i++) value = (value << 8) | in.u8();
        return value;
    }

    @Override
    public boolean canSeek() {
        return in.canSeek() && firstCluster >= 0;
    }

    @Override
    public long seek(long targetMs) throws IOException {
        long target = Math.max(0, targetMs) * 1_000_000L / timecodeScale;
        queue.clear();
        if (cueTimes != null && cueTimes.length > 0) {
            int best = 0;
            for (int i = 0; i < cueTimes.length; i++) {
                if (cueTimes[i] <= target) best = i;
            }
            in.seek(cuePositions[best]);
            return cueTimes[best] * timecodeScale / 1_000_000;
        }

        long position = firstCluster;
        long time = 0;
        in.seek(firstCluster);
        while (in.position() < segmentEnd && !in.eof()) {
            long start = in.position();
            long id = id();
            long size = size();
            if (id != CLUSTER || size == UNKNOWN) break;
            long end = in.position() + size;
            long clusterStart = -1;
            if (id() == CLUSTER_TIMECODE) clusterStart = uint(size());
            if (clusterStart > target) break;
            position = start;
            time = Math.max(0, clusterStart);
            in.seek(end);
        }
        in.seek(position);
        return time * timecodeScale / 1_000_000;
    }
}
