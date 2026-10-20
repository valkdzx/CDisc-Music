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
    void readsTheVersion() throws Exception {
        UpdateChecker.Release release = parse("""
                {"tag_name": "v2.2", "draft": false, "prerelease": false,
                 "html_url": "https://github.com/valkdzx/CDisc-Music/releases/tag/v2.2",
                 "assets": [{"name": "cdisc-2.2.jar", "browser_download_url": "https://github.com/x/cdisc-2.2.jar"}]}""");

        assertEquals("2.2", release.version());
        assertTrue(release.supports("26.2"));
    }

    @Test
    void prereleasesAreIgnored() throws Exception {
        assertNull(parse("""
                {"tag_name": "v3.0-beta", "prerelease": true, "html_url": "https://github.com/x", "assets": []}"""));
    }
}
