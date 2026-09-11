package dev.valkdz.cdisc.audio.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SabrFailureTest {

    @Test
    @DisplayName("recognised when every client's failure is joined into one message")
    void joinedMessage() {
        Exception joined = new Exception(
                "(yts.version: 1.18.2) All clients failed to load the item.\n\n"
                + "Client [WEB] failed: No supported audio streams available, available types: \n");

        assertTrue(TrackLoader.isSabrFailure(joined));
    }

    @Test
    @DisplayName("recognised when the client failures hang on as suppressed")
    void suppressedSiblings() {
        Exception aggregate = new Exception("All clients failed to load the item.");
        aggregate.addSuppressed(
                new Exception("No supported audio streams available, available types: "));

        assertTrue(TrackLoader.isSabrFailure(aggregate));
    }

    @Test
    @DisplayName("recognised through the wrappers LavaPlayer puts around it")
    void nestedCause() {
        Exception deep = new Exception("Track failed",
                new Exception("wrapper", new Exception("No supported audio streams available")));

        assertTrue(TrackLoader.isSabrFailure(deep));
    }

    @Test
    @DisplayName("every other client failure is left alone")
    void otherFailures() {
        assertFalse(TrackLoader.isSabrFailure(new Exception("Not success status code: 403")));
        assertFalse(TrackLoader.isSabrFailure(new Exception("This video requires login.")));
        assertFalse(TrackLoader.isSabrFailure(new Exception("The page needs to be reloaded.")));
        assertFalse(TrackLoader.isSabrFailure(new Exception("Video player configuration error")));
        assertFalse(TrackLoader.isSabrFailure(null));
    }

    @Test
    @DisplayName("a cause cycle terminates")
    void causeCycle() {
        Exception first = new Exception("first");
        Exception second = new Exception("second", first);
        first.initCause(second);

        assertFalse(TrackLoader.isSabrFailure(second));
    }
}
