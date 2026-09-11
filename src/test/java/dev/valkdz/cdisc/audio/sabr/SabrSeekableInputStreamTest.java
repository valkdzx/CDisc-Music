package dev.valkdz.cdisc.audio.sabr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SabrSeekableInputStreamTest {

    private static final int SIZE = 600_000;

    private static final int PAST_THE_WINDOW = 300_000;

    private final AtomicInteger conversations = new AtomicInteger();

    private static byte expected(int index) {
        return (byte) (index % 251);
    }

    private SabrSeekableInputStream open() {
        byte[] data = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) {
            data[i] = expected(i);
        }

        return new SabrSeekableInputStream(SIZE, () -> {
            conversations.incrementAndGet();
            return new ByteArrayInputStream(data);
        });
    }

    private static byte[] readFully(SabrSeekableInputStream stream, int count) throws IOException {
        byte[] out = new byte[count];
        int filled = 0;

        while (filled < count) {
            int taken = stream.read(out, filled, count - filled);
            if (taken < 0) break;
            filled += taken;
        }
        return out;
    }

    private static void assertBytesFrom(byte[] actual, int start) {
        for (int i = 0; i < actual.length; i++) {
            assertEquals(expected(start + i), actual[i],
                    "byte at " + (start + i));
        }
    }

    @Test
    @DisplayName("reading straight through gives the bytes in order")
    void readsForward() throws Exception {
        try (SabrSeekableInputStream stream = open()) {
            assertBytesFrom(readFully(stream, 100), 0);

            assertEquals(100L, stream.getPosition());
            assertEquals(1, conversations.get());
        }
    }

    @Test
    @DisplayName("the container probe's rewind costs no second request")
    void rewindWithinWindow() throws Exception {
        try (SabrSeekableInputStream stream = open()) {
            readFully(stream, 100);
            stream.seek(0);

            assertEquals(0L, stream.getPosition());
            assertEquals(1, conversations.get());
            assertBytesFrom(readFully(stream, 100), 0);
        }
    }

    @Test
    @DisplayName("reading on past a replay is continuous")
    void replayJoinsTheLiveStream() throws Exception {
        try (SabrSeekableInputStream stream = open()) {
            readFully(stream, 100);
            stream.seek(0);
            readFully(stream, 100);

            assertBytesFrom(readFully(stream, 100), 100);
            assertEquals(200L, stream.getPosition());
            assertEquals(1, conversations.get());
        }
    }

    @Test
    @DisplayName("a partial rewind lands on the byte it asked for")
    void partialRewind() throws Exception {
        try (SabrSeekableInputStream stream = open()) {
            readFully(stream, 200);
            stream.seek(50);

            assertEquals(50L, stream.getPosition());
            assertBytesFrom(readFully(stream, 10), 50);
            assertEquals(1, conversations.get());
        }
    }

    @Test
    @DisplayName("a forward seek reads through rather than restarting")
    void forwardSeek() throws Exception {
        try (SabrSeekableInputStream stream = open()) {
            readFully(stream, 100);
            stream.seek(PAST_THE_WINDOW);

            assertEquals(PAST_THE_WINDOW, stream.getPosition());
            assertBytesFrom(readFully(stream, 16), PAST_THE_WINDOW);
            assertEquals(1, conversations.get());
        }
    }

    @Test
    @DisplayName("a rewind past the held window starts the conversation over")
    void rewindBeyondWindow() throws Exception {
        try (SabrSeekableInputStream stream = open()) {
            stream.seek(PAST_THE_WINDOW);
            readFully(stream, 16);

            stream.seek(0);
            assertEquals(0L, stream.getPosition());

            assertEquals(1, conversations.get());

            assertBytesFrom(readFully(stream, 100), 0);
            assertEquals(2, conversations.get());
        }
    }

    @Test
    @DisplayName("the window is rebuilt after a restart")
    void windowSurvivesRestart() throws Exception {
        try (SabrSeekableInputStream stream = open()) {
            stream.seek(PAST_THE_WINDOW);
            readFully(stream, 16);
            stream.seek(0);
            readFully(stream, 100);

            stream.seek(0);
            assertBytesFrom(readFully(stream, 50), 0);
            assertEquals(2, conversations.get());
        }
    }

    @Test
    @DisplayName("byte-at-a-time reads agree with block reads")
    void singleByteReads() throws Exception {
        try (SabrSeekableInputStream stream = open()) {
            stream.seek(1000);

            assertEquals(expected(1000), (byte) stream.read());
            assertEquals(expected(1001), (byte) stream.read());
            assertEquals(1002L, stream.getPosition());
        }
    }

    @Test
    @DisplayName("the end of the stream is the end of the stream")
    void endOfStream() throws Exception {
        try (SabrSeekableInputStream stream = open()) {
            stream.seek(SIZE - 4);

            assertBytesFrom(readFully(stream, 4), SIZE - 4);
            assertEquals(-1, stream.read());
        }
    }

    @Test
    @DisplayName("the content length is the one the player response gave")
    void contentLength() throws Exception {
        try (SabrSeekableInputStream stream = open()) {
            assertEquals(SIZE, stream.getContentLength());
            assertTrue(stream.canSeekHard());
        }
    }
}
