package dev.valkdz.cdisc.audio.sabr;

import java.util.ArrayList;
import java.util.List;

public final class SabrMessages {

    private SabrMessages() {
    }

    private static final int TRACK_TYPES_AUDIO_ONLY = 1;

    public static final int PROTECTION_STATUS_OK = 1;

    public static final int PROTECTION_STATUS_LIMITED = 2;

    public static final int PROTECTION_STATUS_BLOCKED = 3;

    public record FormatId(int itag, long lastModified, String xtags) {

        Protobuf.Writer write() {
            Protobuf.Writer writer = new Protobuf.Writer().varint(1, itag);
            if (lastModified != 0) writer.varint(2, lastModified);
            if (xtags != null && !xtags.isEmpty()) writer.string(3, xtags);
            return writer;
        }

        static FormatId read(Protobuf.Reader reader) {
            int itag = 0;
            long lastModified = 0;
            String xtags = null;

            while (reader.next()) {
                switch (reader.field()) {
                    case 1 -> itag = reader.intValue();
                    case 2 -> lastModified = reader.longValue();
                    case 3 -> xtags = reader.stringValue();
                    default -> { }
                }
            }
            return new FormatId(itag, lastModified, xtags);
        }
    }

    public record MediaHeader(int headerId, int itag, long lastModified, int sequenceNumber,
                              long startMs, long durationMs, long contentLength, boolean initSegment) {

        static MediaHeader parse(byte[] body, int offset, int length) {
            Protobuf.Reader reader = new Protobuf.Reader(body, offset, length);

            int headerId = 0;
            int itag = 0;
            long lastModified = 0;
            int sequenceNumber = 0;
            long startMs = 0;
            long durationMs = 0;
            long contentLength = 0;
            boolean initSegment = false;

            while (reader.next()) {
                switch (reader.field()) {
                    case 1 -> headerId = reader.intValue();
                    case 3 -> itag = reader.intValue();
                    case 4 -> lastModified = reader.longValue();
                    case 8 -> initSegment = reader.boolValue();
                    case 9 -> sequenceNumber = reader.intValue();
                    case 11 -> startMs = reader.longValue();
                    case 12 -> durationMs = reader.longValue();

                    case 13 -> {
                        FormatId id = FormatId.read(reader.messageValue());
                        if (itag == 0) itag = id.itag();
                        if (lastModified == 0) lastModified = id.lastModified();
                    }
                    case 14 -> contentLength = reader.longValue();
                    default -> { }
                }
            }
            return new MediaHeader(headerId, itag, lastModified, sequenceNumber,
                    startMs, durationMs, contentLength, initSegment);
        }
    }

    public record NextRequestPolicy(int backoffMs, byte[] playbackCookie) {

        static NextRequestPolicy parse(byte[] body, int offset, int length) {
            Protobuf.Reader reader = new Protobuf.Reader(body, offset, length);
            int backoffMs = 0;
            byte[] cookie = null;

            while (reader.next()) {
                switch (reader.field()) {
                    case 4 -> backoffMs = reader.intValue();
                    case 7 -> cookie = reader.bytesValue();
                    default -> { }
                }
            }
            return new NextRequestPolicy(backoffMs, cookie);
        }
    }

    public record SabrContext(int type, byte[] value, boolean sendByDefault) {

        static SabrContext parse(byte[] body, int offset, int length) {
            Protobuf.Reader reader = new Protobuf.Reader(body, offset, length);
            int type = 0;
            byte[] value = null;
            boolean sendByDefault = false;

            while (reader.next()) {
                switch (reader.field()) {
                    case 1 -> type = reader.intValue();
                    case 3 -> value = reader.bytesValue();
                    case 4 -> sendByDefault = reader.boolValue();
                    default -> { }
                }
            }
            return new SabrContext(type, value, sendByDefault);
        }

        Protobuf.Writer write() {
            return new Protobuf.Writer().varint(1, type).bytes(2, value);
        }
    }

    public record SabrError(String type, int code) {

        static SabrError parse(byte[] body, int offset, int length) {
            Protobuf.Reader reader = new Protobuf.Reader(body, offset, length);
            String type = null;
            int code = 0;

            while (reader.next()) {
                switch (reader.field()) {
                    case 1 -> type = reader.stringValue();
                    case 2 -> code = reader.intValue();
                    default -> { }
                }
            }
            return new SabrError(type, code);
        }

        @Override
        public String toString() {
            return (type == null ? "unknown" : type) + " (code " + code + ")";
        }
    }

    static String parseRedirectUrl(byte[] body, int offset, int length) {
        Protobuf.Reader reader = new Protobuf.Reader(body, offset, length);
        while (reader.next()) {
            if (reader.field() == 1) return reader.stringValue();
        }
        return null;
    }

    static int parseProtectionStatus(byte[] body, int offset, int length) {
        Protobuf.Reader reader = new Protobuf.Reader(body, offset, length);
        while (reader.next()) {
            if (reader.field() == 1) return reader.intValue();
        }
        return 0;
    }

    public static final class RequestState {
        public byte[] ustreamerConfig;
        public byte[] poToken;
        public byte[] playbackCookie;
        public final List<SabrContext> contexts = new ArrayList<>();

        public long playerTimeMs;

        public FormatId format;

        public FormatId videoFormat;

        public int lastSegmentIndex = -1;

        public int clientName;
        public String clientVersion;
        public String osName;
        public String osVersion;
    }

    public static byte[] encodeRequest(RequestState state) {
        Protobuf.Writer abrState = new Protobuf.Writer()
                .varint(28, state.playerTimeMs)
                .varint(34, 1)
                .float32(35, 1.0f)
                .varint(40, TRACK_TYPES_AUDIO_ONLY);

        Protobuf.Writer clientInfo = new Protobuf.Writer()
                .varint(16, state.clientName)
                .string(17, state.clientVersion)
                .string(18, state.osName)
                .string(19, state.osVersion);

        Protobuf.Writer streamerContext = new Protobuf.Writer()
                .message(1, clientInfo)
                .bytes(2, state.poToken)
                .bytes(3, state.playbackCookie);

        for (SabrContext context : state.contexts) {
            streamerContext.message(5, context.write());
        }

        // Field 4 is left unset on purpose: the position lives in the abr state,
        // which is where the reference client sends it.
        Protobuf.Writer request = new Protobuf.Writer()
                .message(1, abrState)
                .bytes(5, state.ustreamerConfig)
                .message(16, state.format.write())
                .message(19, streamerContext);

        if (state.videoFormat != null) {
            request.message(17, state.videoFormat.write());
        }

        if (state.lastSegmentIndex >= 0) {
            request.message(2, state.format.write());
            request.message(3, bufferedRange(state));
        }
        return request.toByteArray();
    }

    private static Protobuf.Writer bufferedRange(RequestState state) {
        return new Protobuf.Writer()
                .message(1, state.format.write())
                .varint(2, 0)
                .varint(3, state.playerTimeMs)

                .varint(4, 1)
                .varint(5, state.lastSegmentIndex);
    }
}
