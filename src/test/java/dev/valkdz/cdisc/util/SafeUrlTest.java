package dev.valkdz.cdisc.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "0.1.2.3", "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
            "172.31.255.255", "192.168.1.1", "100.64.0.1", "192.0.0.8", "198.18.0.1",
            "198.19.255.255", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:127.0.0.1",
            "::127.0.0.1", "2002:7f00:1::", "2002:c0a8:101::1", "64:ff9b::a9fe:a9fe"
    })
    void privateAddressesAreRefused(String literal) throws Exception {
        assertFalse(SafeUrl.isPublic(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.1.1.1", "8.8.8.8", "172.32.0.1", "100.128.0.1", "198.20.0.1", "223.255.255.254",
            "2606:4700::1111", "2002:0808:0808::", "64:ff9b::808:808"
    })
    void publicAddressesPass(String literal) throws Exception {
        assertTrue(SafeUrl.isPublic(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/a.mp3", "http://localhost:8080/", "https://[::1]/x",
            "http://169.254.169.254/latest/meta-data/", "http://2130706433/"
    })
    void urlsIntoThisNetworkAreJudgedPrivate(String url) {
        assertEquals(SafeUrl.Verdict.PRIVATE_ADDRESS, SafeUrl.judge(url), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.com/a.mp3", "icy://example.com/"})
    void otherSchemesAreRefused(String url) {
        assertEquals(SafeUrl.Verdict.BAD_SCHEME, SafeUrl.judge(url), url);
    }
}
