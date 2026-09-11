package dev.valkdz.cdisc.audio.sabr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SabrProtocolTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Nested
    @DisplayName("UMP's own variable-width integer")
    class Varints {

        @Test
        @DisplayName("width is decided by the first byte alone")
        void width() {
            assertEquals(1, UmpReader.widthOf(0x00));
            assertEquals(1, UmpReader.widthOf(0x7F));
            assertEquals(2, UmpReader.widthOf(0x80));
            assertEquals(2, UmpReader.widthOf(0xBF));
            assertEquals(3, UmpReader.widthOf(0xC0));
            assertEquals(3, UmpReader.widthOf(0xDF));
            assertEquals(4, UmpReader.widthOf(0xE0));
            assertEquals(4, UmpReader.widthOf(0xEF));
            assertEquals(5, UmpReader.widthOf(0xF0));
            assertEquals(5, UmpReader.widthOf(0xFF));
        }

        @Test
        @DisplayName("the first byte's spare bits are part of the value")
        void firstByteCarriesData() {
            assertEquals(0L, UmpReader.readVarint(bytes(0x00), 0));
            assertEquals(127L, UmpReader.readVarint(bytes(0x7F), 0));
            assertEquals(64L, UmpReader.readVarint(bytes(0x80, 0x01), 0));
            assertEquals(63L + 64L * 255L, UmpReader.readVarint(bytes(0xBF, 0xFF), 0));
            assertEquals(32L, UmpReader.readVarint(bytes(0xC0, 0x01, 0x00), 0));
            assertEquals(31L + 32L * (255L + 256L * 255L),
                    UmpReader.readVarint(bytes(0xDF, 0xFF, 0xFF), 0));
            assertEquals(16L, UmpReader.readVarint(bytes(0xE0, 0x01, 0x00, 0x00), 0));
        }

        @Test
        @DisplayName("the five-byte form is an unsigned little-endian tail")
        void fiveByteForm() {
            assertEquals(0x04030201L, UmpReader.readVarint(bytes(0xF0, 0x01, 0x02, 0x03, 0x04), 0));
            assertEquals(0xFFFFFFFFL, UmpReader.readVarint(bytes(0xF0, 0xFF, 0xFF, 0xFF, 0xFF), 0));
        }

        @Test
        @DisplayName("a value cut off by the end of the body is refused")
        void truncated() {
            assertThrows(IllegalArgumentException.class,
                    () -> UmpReader.readVarint(bytes(0xF0, 0x01), 0));
        }
    }

    @Nested
    @DisplayName("part framing")
    class Framing {

        @Test
        @DisplayName("each part is handed over as a slice of the body")
        void walksEveryPart() {
            byte[] body = bytes(20, 3, 0xAA, 0xBB, 0xCC, 21, 2, 0xDD, 0xEE);

            List<String> seen = new ArrayList<>();
            UmpReader.forEachPart(body, (type, buffer, offset, length) ->
                    seen.add(type + ":" + Arrays.toString(
                            Arrays.copyOfRange(buffer, offset, offset + length))));

            assertEquals(List.of("20:[-86, -69, -52]", "21:[-35, -18]"), seen);
        }

        @Test
        @DisplayName("a part claiming more than is there is refused")
        void refusesOverrun() {
            assertThrows(IllegalArgumentException.class,
                    () -> UmpReader.forEachPart(bytes(20, 9, 0x01), (t, b, o, l) -> { }));
        }

        @Test
        @DisplayName("an empty body is no parts rather than an error")
        void emptyBody() {
            UmpReader.forEachPart(new byte[0], (t, b, o, l) -> {
                throw new AssertionError("no part was expected");
            });
        }
    }

    @Nested
    @DisplayName("protobuf")
    class Wire {

        @Test
        @DisplayName("every type written comes back unchanged")
        void roundTrip() {
            byte[] encoded = new Protobuf.Writer()
                    .varint(1, 300)
                    .string(2, "opus")
                    .bytes(3, bytes(0xDE, 0xAD))
                    .bool(4, true)
                    .float32(5, 1.0f)
                    .message(6, new Protobuf.Writer().varint(1, 251).varint(2, 1780336501433939L))
                    .toByteArray();

            Protobuf.Reader reader = new Protobuf.Reader(encoded);
            List<String> read = new ArrayList<>();

            while (reader.next()) {
                switch (reader.field()) {
                    case 1 -> read.add("varint=" + reader.longValue());
                    case 2 -> read.add("string=" + reader.stringValue());
                    case 3 -> read.add("bytes=" + Arrays.toString(reader.bytesValue()));
                    case 4 -> read.add("bool=" + reader.boolValue());
                    case 5 -> read.add("float=" + Float.intBitsToFloat(reader.intValue()));
                    case 6 -> {
                        Protobuf.Reader nested = reader.messageValue();
                        List<Long> inner = new ArrayList<>();
                        while (nested.next()) {
                            inner.add(nested.longValue());
                        }
                        read.add("message=" + inner);
                    }
                    default -> throw new AssertionError("unexpected field " + reader.field());
                }
            }

            assertEquals(List.of(
                    "varint=300",
                    "string=opus",
                    "bytes=[-34, -83]",
                    "bool=true",
                    "float=1.0",
                    "message=[251, 1780336501433939]"), read);
        }

        @Test
        @DisplayName("unknown fields are stepped over, of every wire type")
        void skipsUnknownFields() {
            byte[] encoded = new Protobuf.Writer()
                    .varint(99, 7)
                    .string(98, "junk")
                    .float32(97, 2f)
                    .varint(1, 42)
                    .toByteArray();

            long found = -1;
            Protobuf.Reader reader = new Protobuf.Reader(encoded);
            while (reader.next()) {
                if (reader.field() == 1) found = reader.longValue();
            }

            assertEquals(42L, found);
        }
    }

    @Nested
    @DisplayName("messages")
    class Messages {

        @Test
        @DisplayName("a media header is read field for field")
        void mediaHeader() {
            byte[] encoded = new Protobuf.Writer()
                    .varint(1, 5)
                    .varint(3, 251)
                    .varint(9, 12)
                    .varint(11, 20_000)
                    .varint(12, 5_000)
                    .varint(14, 123_456)
                    .toByteArray();

            SabrMessages.MediaHeader header = SabrMessages.MediaHeader.parse(encoded, 0, encoded.length);

            assertEquals(5, header.headerId());
            assertEquals(251, header.itag());
            assertEquals(12, header.sequenceNumber());
            assertEquals(25_000L, header.startMs() + header.durationMs());
            assertEquals(123_456L, header.contentLength());
        }

        @Test
        @DisplayName("a header with only the nested format id still yields an itag")
        void mediaHeaderFallsBackToFormatId() {
            byte[] encoded = new Protobuf.Writer()
                    .varint(1, 2)
                    .message(13, new Protobuf.Writer().varint(1, 140).varint(2, 999L))
                    .toByteArray();

            SabrMessages.MediaHeader header = SabrMessages.MediaHeader.parse(encoded, 0, encoded.length);

            assertEquals(140, header.itag());
            assertEquals(999L, header.lastModified());
        }

        @Test
        @DisplayName("a redirect is read as its replacement URL")
        void redirect() {
            byte[] encoded = new Protobuf.Writer().string(1, "https://rr5.googlevideo.com/x").toByteArray();

            assertEquals("https://rr5.googlevideo.com/x",
                    SabrMessages.parseRedirectUrl(encoded, 0, encoded.length));
        }

        @Test
        @DisplayName("the playback cookie is carried as opaque bytes")
        void nextRequestPolicy() {
            byte[] encoded = new Protobuf.Writer()
                    .varint(4, 250)
                    .bytes(7, bytes(0x0A, 0x0B))
                    .toByteArray();

            SabrMessages.NextRequestPolicy policy =
                    SabrMessages.NextRequestPolicy.parse(encoded, 0, encoded.length);

            assertEquals(250, policy.backoffMs());
            assertArrayEquals(bytes(0x0A, 0x0B), policy.playbackCookie());
        }
    }

    @Nested
    @DisplayName("the request")
    class Request {

        private static SabrMessages.RequestState state() {
            SabrMessages.RequestState state = new SabrMessages.RequestState();
            state.format = new SabrMessages.FormatId(251, 1780336501433939L, null);
            state.ustreamerConfig = bytes(0x01, 0x02);
            state.poToken = bytes(0x03);
            state.clientName = 1;
            state.clientVersion = "2.2026";
            state.osName = "Windows";
            state.osVersion = "10.0";
            return state;
        }

        private static Set<Integer> fieldsOf(byte[] message) {
            Set<Integer> present = new TreeSet<>();
            Protobuf.Reader reader = new Protobuf.Reader(message);
            while (reader.next()) {
                present.add(reader.field());
            }
            return present;
        }

        @Test
        @DisplayName("the opening request holds no buffer and selects no format")
        void firstRequest() {
            Set<Integer> fields = fieldsOf(SabrMessages.encodeRequest(state()));

            assertEquals(Set.of(1, 5, 16, 19), fields);
            assertFalse(fields.contains(2), "selected_format_ids");
            assertFalse(fields.contains(3), "buffered_ranges");
        }

        @Test
        @DisplayName("later requests name the selected format and what is held")
        void laterRequest() {
            SabrMessages.RequestState state = state();
            state.lastSegmentIndex = 3;
            state.playerTimeMs = 25_000;

            assertEquals(Set.of(1, 2, 3, 5, 16, 19),
                    fieldsOf(SabrMessages.encodeRequest(state)));
        }

        @Test
        @DisplayName("a video format is named even though only audio is wanted")
        void namesAVideoFormat() {
            SabrMessages.RequestState state = state();
            state.videoFormat = new SabrMessages.FormatId(160, 1780336587389298L, null);

            assertTrue(fieldsOf(SabrMessages.encodeRequest(state)).contains(17),
                    "preferred_video_format_ids");
        }
    }
}
