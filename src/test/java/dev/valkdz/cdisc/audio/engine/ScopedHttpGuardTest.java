package dev.valkdz.cdisc.audio.engine;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import org.apache.http.client.methods.HttpGet;
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
        FriendlyException refused = assertThrows(FriendlyException.class, () -> source.loadItem(
                new DefaultAudioPlayerManager(), new AudioReference("http://127.0.0.1:1/a.mp3", null)));
        assertEquals(ScopedHttpAudioSourceManager.REFUSED, refused.getMessage());
    }

    @Test
    void leavesOtherIdentifiersToOtherSources() {
        ScopedHttpAudioSourceManager any = new ScopedHttpAudioSourceManager();
        assertNull(any.loadItem(new DefaultAudioPlayerManager(), new AudioReference("ytsearch:song", null)));
        assertNull(any.loadItem(new DefaultAudioPlayerManager(), new AudioReference("icy://127.0.0.1/", null)));

        ScopedHttpAudioSourceManager scoped = new ScopedHttpAudioSourceManager("https://cdn.example.com/");
        assertNull(scoped.loadItem(new DefaultAudioPlayerManager(),
                new AudioReference("http://127.0.0.1:1/a.mp3", null)));
    }

    @Test
    void neverOpensAConnectionToThisNetwork() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(300);
            ScopedHttpAudioSourceManager source = new ScopedHttpAudioSourceManager();

            try (HttpInterface http = source.getHttpInterface()) {
                IOException refused = assertThrows(IOException.class, () ->
                        http.execute(new HttpGet("http://127.0.0.1:" + server.getLocalPort() + "/a.mp3")));
                assertTrue(String.valueOf(refused.getMessage()).contains(ScopedHttpAudioSourceManager.REFUSED)
                        || String.valueOf(refused.getCause()).contains(ScopedHttpAudioSourceManager.REFUSED),
                        String.valueOf(refused));
            }
            assertThrows(SocketTimeoutException.class, server::accept);
        }
    }
}
