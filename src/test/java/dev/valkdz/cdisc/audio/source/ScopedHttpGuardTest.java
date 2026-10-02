package dev.valkdz.cdisc.audio.source;

import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.audio.player.LoadException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedHttpGuardTest {

    @Test
    void refusesToLoadALinkIntoThisNetwork() {
        ScopedHttpAudioSourceManager source = new ScopedHttpAudioSourceManager();
        LoadException refused = assertThrows(LoadException.class,
                () -> source.loadItem("http://127.0.0.1:1/a.mp3"));
        assertEquals(ScopedHttpAudioSourceManager.REFUSED, refused.getMessage());
    }

    @Test
    void leavesOtherIdentifiersToOtherSources() {
        ScopedHttpAudioSourceManager any = new ScopedHttpAudioSourceManager();
        assertNull(any.loadItem("ytsearch:song"));
        assertNull(any.loadItem("icy://127.0.0.1/"));

        ScopedHttpAudioSourceManager scoped = new ScopedHttpAudioSourceManager("https://cdn.example.com/");
        assertNull(scoped.loadItem("http://127.0.0.1:1/a.mp3"));
    }

    @Test
    void neverOpensAConnectionToThisNetwork() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(300);
            IOException refused = assertThrows(IOException.class, () -> Http.open(
                    "http://127.0.0.1:" + server.getLocalPort() + "/a.mp3", 0, -1,
                    ScopedHttpAudioSourceManager.PUBLIC_ONLY));
            assertTrue(String.valueOf(refused.getMessage()).contains(ScopedHttpAudioSourceManager.REFUSED),
                    String.valueOf(refused));
            assertThrows(SocketTimeoutException.class, server::accept);
        }
    }
}
