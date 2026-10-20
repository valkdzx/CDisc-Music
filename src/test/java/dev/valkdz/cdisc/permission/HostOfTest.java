package dev.valkdz.cdisc.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HostOfTest {

    @Test
    void plainLinks() {
        assertEquals("soundcloud.com", PermissionsConfig.hostOf("https://soundcloud.com/a/b"));
        assertEquals("youtube.com", PermissionsConfig.hostOf("https://www.YouTube.com/watch?v=x"));
        assertEquals("cdn.example.org", PermissionsConfig.hostOf("http://user:pw@cdn.example.org:8080/x.mp3"));
        assertEquals("[::1]", PermissionsConfig.hostOf("http://[::1]:80/x"));
    }

    @Test
    void searchesAreNotLinks() {
        assertNull(PermissionsConfig.hostOf("some song"));
        assertNull(PermissionsConfig.hostOf("sc:some song"));
        assertNull(PermissionsConfig.hostOf("local:music/a.mp3"));
    }

    @Test
    void sourcePrefixDoesNotHideTheLink() {
        assertEquals("blocked.com", PermissionsConfig.hostOf("tt:https://blocked.com/x.mp3"));
        assertEquals("blocked.com", PermissionsConfig.hostOf("TT: https://blocked.com/x.mp3"));
    }

    @Test
    void trailingDotIsTheSameHost() {
        assertEquals("blocked.com", PermissionsConfig.hostOf("https://blocked.com./x.mp3"));
    }

    @Test
    void linksUriRefusesStillHaveAHost() {
        assertEquals("soundcloud.com", PermissionsConfig.hostOf("https://soundcloud.com/a b"));
        assertEquals("my_host.com", PermissionsConfig.hostOf("http://my_host.com/x"));
        assertEquals("blocked.com", PermissionsConfig.hostOf("https://blocked.com\\@other.com/x"));
    }
}
