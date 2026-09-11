package dev.valkdz.cdisc.youtube;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitorIdentityTest {

    private static final String REAL_VISITOR_DATA =
            "CgtLTG1ZNDdUdWh3dyi4t9zUBjIKCgJJTBIEGgAgSmLfAg";

    private static final String REAL_IDENTITY = "KLmY47Tuhww";

    @Nested
    @DisplayName("reading the identity")
    class Reading {

        @Test
        @DisplayName("the eleven characters at the front of a real string")
        void realString() {
            assertEquals(REAL_IDENTITY, VisitorRenewal.identityOf(REAL_VISITOR_DATA));
        }

        @Test
        @DisplayName("percent-encoded, the way a page carries it")
        void percentEncoded() {

            assertEquals(REAL_IDENTITY,
                    VisitorRenewal.identityOf(REAL_VISITOR_DATA + "%3D%3D"));
        }

        @Test
        @DisplayName("nothing at all, rather than a guess")
        void missing() {
            assertNull(VisitorRenewal.identityOf(null));
            assertNull(VisitorRenewal.identityOf(""));
            assertNull(VisitorRenewal.identityOf("   "));
        }

        @Test
        @DisplayName("not base64, and not a crash")
        void notBase64() {
            assertNull(VisitorRenewal.identityOf("this is not a token at all"));
        }

        @Test
        @DisplayName("base64 of something that isn't a visitor-data")
        void wrongShape() {

            assertNull(VisitorRenewal.identityOf("EAEYAg"));
        }

        @Test
        @DisplayName("a length running past the end of the string")
        void truncated() {

            String truncated = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(new byte[] {0x0a, 40, 'a', 'b', 'c', 'd'});
            assertNull(VisitorRenewal.identityOf(truncated));
        }
    }

    @Nested
    @DisplayName("the file the pair lives in")
    class Cache {

        @Test
        @DisplayName("written and read back whole")
        void roundTrip(@TempDir Path folder) throws Exception {
            TokenCache cache = new TokenCache(folder.toFile());
            cache.write("a-token", REAL_VISITOR_DATA, 3600, "renewal");

            TokenCache.Entry read = cache.read();
            assertNotNull(read);
            assertEquals("a-token", read.poToken());
            assertEquals(REAL_VISITOR_DATA, read.visitorData());
            assertEquals(REAL_IDENTITY, read.visitorId());
            assertEquals("renewal", read.source());
            assertTrue(read.isUsable());
        }

        @Test
        @DisplayName("a fresh pair has most of its life left")
        void remaining(@TempDir Path folder) throws Exception {
            TokenCache cache = new TokenCache(folder.toFile());
            cache.write("a-token", REAL_VISITOR_DATA, 3600, "renewal");

            long left = cache.read().remainingSeconds();
            assertTrue(left > 3500 && left <= 3600, "expected nearly an hour, got " + left);
        }

        @Test
        @DisplayName("a spent pair reports nothing left rather than a negative")
        void spent() {

            TokenCache.Entry old = new TokenCache.Entry(
                    "a-token", REAL_VISITOR_DATA, REAL_IDENTITY,
                    java.time.Instant.now().getEpochSecond() - 3600, 60, "renewal");

            assertEquals(0, old.remainingSeconds());
        }

        @Test
        @DisplayName("no file yet is not an error")
        void absent(@TempDir Path folder) {
            assertNull(new TokenCache(folder.toFile()).read());
        }

        @Test
        @DisplayName("a truncated file is ignored, not fatal")
        void mangled(@TempDir Path folder) throws Exception {
            File file = new File(folder.toFile(), TokenCache.FILE_NAME);
            Files.writeString(file.toPath(), "{\"visitor-data\": \"CgtL",
                    StandardCharsets.UTF_8);

            assertNull(new TokenCache(folder.toFile()).read());
        }

        @Test
        @DisplayName("a pair with no visitor-data is no pair")
        void empty(@TempDir Path folder) throws Exception {
            TokenCache cache = new TokenCache(folder.toFile());
            cache.write("a-token", "", 3600, "renewal");

            assertNull(cache.read());
        }

        @Test
        @DisplayName("the identity is recovered when the file omits it")
        void identityDerived(@TempDir Path folder) throws Exception {

            File file = new File(folder.toFile(), TokenCache.FILE_NAME);
            Files.writeString(file.toPath(),
                    "{\"visitor-data\": \"" + REAL_VISITOR_DATA + "\","
                    + "\"updated-at\": 1, \"expires-in-seconds\": 3600}",
                    StandardCharsets.UTF_8);

            TokenCache.Entry read = new TokenCache(folder.toFile()).read();
            assertNotNull(read);
            assertEquals(REAL_IDENTITY, read.visitorId());
        }
    }

}
