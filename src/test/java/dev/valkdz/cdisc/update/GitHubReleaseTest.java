package dev.valkdz.cdisc.update;

import dev.valkdz.cdisc.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseTest {

    private static UpdateChecker.Release parse(String json) throws Exception {
        return UpdateChecker.githubRelease(Json.parse(json));
    }

    @Test
    void readsTheJarAndItsDigest() throws Exception {
        UpdateChecker.Release release = parse("""
                {"tag_name": "v2.2", "draft": false, "prerelease": false,
                 "html_url": "https://github.com/valkdzx/CDisc-Music/releases/tag/v2.2",
                 "assets": [
                   {"name": "sources.zip", "digest": "sha256:00",
                    "browser_download_url": "https://github.com/x/sources.zip"},
                   {"name": "cdisc-2.2.jar", "digest": "sha256:ABCDEF",
                    "browser_download_url": "https://github.com/x/cdisc-2.2.jar"}
                 ]}""");

        assertEquals("2.2", release.version());
        assertEquals("https://github.com/valkdzx/CDisc-Music/releases/tag/v2.2", release.page());
        assertTrue(release.supports("26.2"));
        assertEquals("cdisc-2.2.jar", release.download().fileName());
        assertEquals("SHA-256", release.download().hashAlgorithm());
        assertEquals("ABCDEF", release.download().hash());
    }

    @Test
    void aJarWithoutADigestIsNotDownloaded() throws Exception {
        UpdateChecker.Release release = parse("""
                {"tag_name": "2.2", "html_url": "https://github.com/x",
                 "assets": [{"name": "cdisc-2.2.jar", "browser_download_url": "https://github.com/x/a.jar"}]}""");
        assertEquals("2.2", release.version());
        assertNull(release.download());
    }

    @Test
    void prereleasesAreIgnored() throws Exception {
        assertNull(parse("""
                {"tag_name": "v3.0-beta", "prerelease": true, "html_url": "https://github.com/x", "assets": []}"""));
    }
}
