package dev.valkdz.cdisc.audio.media;

import dev.valkdz.cdisc.audio.media.codec.AacDecoder;
import dev.valkdz.cdisc.audio.media.codec.FlacDecoder;
import dev.valkdz.cdisc.audio.media.codec.Mp3Decoder;
import dev.valkdz.cdisc.audio.media.codec.PcmDecoder;
import dev.valkdz.cdisc.audio.media.codec.VorbisDecoder;
import dev.valkdz.cdisc.audio.media.container.AdtsReader;
import dev.valkdz.cdisc.audio.media.container.FlacReader;
import dev.valkdz.cdisc.audio.media.container.MatroskaReader;
import dev.valkdz.cdisc.audio.media.container.Mp3Reader;
import dev.valkdz.cdisc.audio.media.container.Mp4Reader;
import dev.valkdz.cdisc.audio.media.container.OggReader;
import dev.valkdz.cdisc.audio.media.container.WavReader;

import java.io.IOException;
import java.util.Locale;

public final class Media {

    private static final int PROBE = 4096;

    private Media() {
    }

    public enum Container { MP4, MATROSKA, OGG, FLAC, WAV, MP3, ADTS, UNKNOWN }

    public static Demuxer open(MediaInput input, String mimeType) throws IOException {
        long start = input.position();
        byte[] head = new byte[PROBE];
        int got = 0;
        while (got < PROBE) {
            int read = input.read(head, got, PROBE - got);
            if (read < 0) break;
            got += read;
        }
        MediaInput replay = input.canSeek() ? seekBack(input, start) : new Replay(head, got, input, start);

        Container container = detect(head, got);
        if (container == Container.UNKNOWN) container = fromMime(mimeType);
        return switch (container) {
            case MP4 -> new Mp4Reader(replay);
            case MATROSKA -> new MatroskaReader(replay);
            case OGG -> new OggReader(replay);
            case FLAC -> new FlacReader(replay);
            case WAV -> new WavReader(replay);
            case ADTS -> new AdtsReader(replay);
            case MP3 -> new Mp3Reader(replay);
            case UNKNOWN -> throw new IOException("The stream is in no format we recognise"
                    + (mimeType == null ? "" : " (" + mimeType + ")"));
        };
    }

    private static MediaInput seekBack(MediaInput input, long start) throws IOException {
        input.seek(start);
        return input;
    }

    public static Container detect(byte[] h, int length) {
        if (length < 12) return Container.UNKNOWN;
        if (is(h, 0, "fLaC")) return Container.FLAC;
        if (is(h, 0, "OggS")) return Container.OGG;
        if ((h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) {
            return Container.MATROSKA;
        }
        if ((is(h, 0, "RIFF") || is(h, 0, "RF64")) && is(h, 8, "WAVE")) return Container.WAV;
        if (is(h, 0, "FORM") && (is(h, 8, "AIFF") || is(h, 8, "AIFC"))) return Container.WAV;
        if (is(h, 4, "ftyp") || is(h, 4, "moov") || is(h, 4, "styp") || is(h, 4, "sidx")
                || is(h, 4, "moof") || is(h, 4, "free") || is(h, 4, "wide") || is(h, 4, "skip")
                || (is(h, 4, "mdat") && length > 16)) {
            return Container.MP4;
        }
        int at = 0;
        if (is(h, 0, "ID3") && length >= 10) {
            int size = ((h[6] & 0x7F) << 21) | ((h[7] & 0x7F) << 14) | ((h[8] & 0x7F) << 7) | (h[9] & 0x7F);
            at = 10 + size + ((h[5] & 0x10) != 0 ? 10 : 0);
            if (at + 4 > length) return Container.MP3;
            if (is(h, at, "fLaC")) return Container.FLAC;
        }
        for (int i = at; i + 4 <= length; i++) {
            if ((h[i] & 0xFF) != 0xFF || (h[i + 1] & 0xE0) != 0xE0) continue;
            if ((h[i + 1] & 0x06) == 0) {
                if ((h[i + 1] & 0xF0) == 0xF0) return Container.ADTS;
            } else {
                int[] header = {h[i] & 0xFF, h[i + 1] & 0xFF, h[i + 2] & 0xFF, h[i + 3] & 0xFF};
                if (Mp3Decoder.hdrValid(header)) return Container.MP3;
            }
        }
        return Container.UNKNOWN;
    }

    private static Container fromMime(String mimeType) {
        if (mimeType == null) return Container.UNKNOWN;
        String m = mimeType.toLowerCase(Locale.ROOT);
        if (m.contains("mp4") || m.contains("m4a")) return Container.MP4;
        if (m.contains("webm") || m.contains("matroska")) return Container.MATROSKA;
        if (m.contains("ogg") || m.contains("opus")) return Container.OGG;
        if (m.contains("flac")) return Container.FLAC;
        if (m.contains("wav")) return Container.WAV;
        if (m.contains("aac")) return Container.ADTS;
        if (m.contains("mpeg") || m.contains("mp3")) return Container.MP3;
        return Container.UNKNOWN;
    }

    private static boolean is(byte[] h, int at, String text) {
        if (at + text.length() > h.length) return false;
        for (int i = 0; i < text.length(); i++) if (h[at + i] != text.charAt(i)) return false;
        return true;
    }

    public static AudioDecoder decoder(TrackFormat format) throws IOException {
        return switch (format.codec()) {
            case MP3 -> new Mp3Decoder(format.sampleRate(), format.channels());
            case AAC -> new AacDecoder(format.config());
            case FLAC -> new FlacDecoder(format.config());
            case PCM -> new PcmDecoder(format);
            case VORBIS -> new VorbisDecoder(format.headers());
            case OPUS -> throw new IOException(format.codec() + " is not supported yet");
        };
    }

    private static final class Replay extends MediaInput {
        private final byte[] head;
        private final int headLength;
        private final MediaInput rest;
        private final long start;
        private int at;

        Replay(byte[] head, int headLength, MediaInput rest, long start) {
            this.head = head;
            this.headLength = headLength;
            this.rest = rest;
            this.start = start;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (at < headLength) {
                int taken = Math.min(length, headLength - at);
                System.arraycopy(head, at, buffer, offset, taken);
                at += taken;
                return taken;
            }
            return rest.read(buffer, offset, length);
        }

        @Override
        public long position() {
            return at < headLength ? start + at : rest.position();
        }

        @Override
        public long length() {
            return rest.length();
        }

        @Override
        public void close() throws IOException {
            rest.close();
        }
    }
}
