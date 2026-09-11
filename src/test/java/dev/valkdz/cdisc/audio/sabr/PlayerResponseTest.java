package dev.valkdz.cdisc.audio.sabr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static InnerTubePlayer.PlayerResponse parse(String json) throws JsonProcessingException {
        return InnerTubePlayer.parse(MAPPER.readTree(json));
    }

    private static final String SABR_ONLY = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {"title": "A song", "author": "A band", "lengthSeconds": "207"},
              "playerConfig": {"mediaCommonConfig": {"mediaUstreamerRequestConfig": {
                  "videoPlaybackUstreamerConfig": "CgtIZWxsbw"
              }}},
              "streamingData": {
                "serverAbrStreamingUrl": "https://rr1.googlevideo.com/videoplayback?sabr=1",
                "adaptiveFormats": [
                  {"itag":137,"mimeType":"video/mp4; codecs=\\"avc1.640020\\"","bitrate":76497,
                   "lastModified":"1780336532074061"},
                  {"itag":140,"mimeType":"audio/mp4; codecs=\\"mp4a.40.2\\"","bitrate":130928,
                   "lastModified":"1780336505326779","contentLength":"3355438","approxDurationMs":"207151"},
                  {"itag":249,"mimeType":"audio/webm; codecs=\\"opus\\"","bitrate":51262,
                   "lastModified":"1780336521937438","contentLength":"1208373","approxDurationMs":"207161"},
                  {"itag":251,"mimeType":"audio/webm; codecs=\\"opus\\"","bitrate":120611,
                   "lastModified":"1780336501433939","contentLength":"2869349","approxDurationMs":"207161"}
                ]
              }
            }
            """;

    private static final String CLASSIC = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {"title": "t", "author": "a", "lengthSeconds": "10"},
              "streamingData": {
                "adaptiveFormats": [
                  {"itag":251,"mimeType":"audio/webm; codecs=\\"opus\\"","bitrate":120611,
                   "lastModified":"1","contentLength":"5",
                   "url":"https://rr1.googlevideo.com/videoplayback?x=1"}
                ]
              }
            }
            """;

    private static final String REFUSED = """
            {"playabilityStatus": {"status": "LOGIN_REQUIRED", "reason": "This video requires login."},
             "streamingData": {}, "videoDetails": {}}
            """;

    @Nested
    @DisplayName("picking the video out of what was typed")
    class VideoIds {

        @Test
        @DisplayName("every shape of YouTube link yields its id")
        void recognisedForms() {
            assertEquals("aRiWonrqsP8",
                    SabrResolver.videoIdOf("https://www.youtube.com/watch?v=aRiWonrqsP8"));
            assertEquals("a-x7Apy0RIA",
                    SabrResolver.videoIdOf("https://www.youtube.com/watch?list=X&v=a-x7Apy0RIA&t=5"));
            assertEquals("aRiWonrqsP8",
                    SabrResolver.videoIdOf("https://youtu.be/aRiWonrqsP8?si=zz"));
            assertEquals("aRiWonrqsP8",
                    SabrResolver.videoIdOf("https://www.youtube.com/shorts/aRiWonrqsP8"));
            assertEquals("aRiWonrqsP8",
                    SabrResolver.videoIdOf("https://www.youtube.com/embed/aRiWonrqsP8"));
            assertEquals("aRiWonrqsP8", SabrResolver.videoIdOf("aRiWonrqsP8"));
        }

        @Test
        @DisplayName("anything that is not a YouTube video is left alone")
        void otherIdentifiers() {
            assertNull(SabrResolver.videoIdOf("https://open.spotify.com/track/7kSpl8d5G1FaljqVVwh8ki"));
            assertNull(SabrResolver.videoIdOf("ytsearch:cupsize"));
            assertNull(SabrResolver.videoIdOf(null));
        }
    }

    @Nested
    @DisplayName("a SABR-only response")
    class SabrOnly {

        @Test
        @DisplayName("the ustreamer config is found and decoded")
        void ustreamerConfig() throws Exception {
            assertNotNull(parse(SABR_ONLY).ustreamerConfig());
        }

        @Test
        @DisplayName("it is recognised as having no direct links at all")
        void recognised() throws Exception {
            InnerTubePlayer.PlayerResponse response = parse(SABR_ONLY);

            assertTrue(response.isPlayable());
            assertTrue(response.isSabrOnly());
            assertEquals("https://rr1.googlevideo.com/videoplayback?sabr=1",
                    response.serverAbrStreamingUrl());
        }

        @Test
        @DisplayName("video formats are dropped, audio kept")
        void audioOnly() throws Exception {
            assertEquals(3, parse(SABR_ONLY).audioFormats().size());
        }

        @Test
        @DisplayName("duration comes from the video details")
        void duration() throws Exception {
            assertEquals(207_000L, parse(SABR_ONLY).durationMs());
        }

        @Test
        @DisplayName("opus is chosen over a higher-bitrate aac")
        void prefersOpus() throws Exception {
            InnerTubePlayer.AudioFormat best = parse(SABR_ONLY).bestAudio().orElseThrow();

            assertEquals(251, best.itag());
            assertTrue(best.isOpus());
        }

        @Test
        @DisplayName("the format id keeps what identifies the encoding")
        void formatIdIsComplete() throws Exception {
            SabrMessages.FormatId id = parse(SABR_ONLY).bestAudio().orElseThrow().formatId();

            assertEquals(251, id.itag());
            assertEquals(1780336501433939L, id.lastModified());
        }

        @Test
        @DisplayName("content length is carried, so the stream can be sized")
        void contentLength() throws Exception {
            assertEquals(2869349L, parse(SABR_ONLY).bestAudio().orElseThrow().contentLength());
        }
    }

    @Nested
    @DisplayName("responses that are not SABR")
    class NotSabr {

        @Test
        @DisplayName("a response with direct links is not SABR-only")
        void classicResponse() throws Exception {
            InnerTubePlayer.PlayerResponse response = parse(CLASSIC);

            assertTrue(response.bestAudio().orElseThrow().hasDirectUrl());
            assertFalse(response.isSabrOnly());
        }

        @Test
        @DisplayName("a refusal is reported as itself, not as SABR")
        void refusal() throws Exception {
            InnerTubePlayer.PlayerResponse response = parse(REFUSED);

            assertFalse(response.isPlayable());
            assertEquals("LOGIN_REQUIRED", response.playabilityStatus());
            assertEquals("This video requires login.", response.playabilityReason());
            assertFalse(response.isSabrOnly());
        }
    }
}
