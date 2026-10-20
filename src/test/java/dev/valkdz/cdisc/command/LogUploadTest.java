package dev.valkdz.cdisc.command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogUploadTest {

    private static List<String> problems(String... lines) {
        return LogUpload.problems(List.of(lines).iterator());
    }

    @Test
    void keepsOnlyCdiscWarningsAndErrorsWithTheirTraces() {
        List<String> kept = problems(
                "\tat a.partial.Trace(From.java:1)",
                "[12:00:00 INFO]: [CDisc] Enabling CDisc v2.2.2",
                "[12:00:01 INFO]: Player123 joined the game",
                "[12:00:02 WARN]: [CDisc] The direct read could not serve the track: HTTP 403",
                "[12:00:03 ERROR]: [OtherPlugin] Database is down",
                "\tat other.Plugin.run(Plugin.java:5)",
                "[12:00:04 ERROR]: Could not pass event PlayerInteractEvent to CDisc v2.2.2",
                "java.lang.NullPointerException: boom",
                "\tat dev.valkdz.cdisc.jukebox.JukeboxListener.onJukeboxInteract(JukeboxListener.java:160)",
                "[12:00:05 INFO]: Done");

        assertEquals(List.of(
                "[12:00:02 WARN]: [CDisc] The direct read could not serve the track: HTTP 403",
                "",
                "[12:00:04 ERROR]: Could not pass event PlayerInteractEvent to CDisc v2.2.2",
                "java.lang.NullPointerException: boom",
                "\tat dev.valkdz.cdisc.jukebox.JukeboxListener.onJukeboxInteract(JukeboxListener.java:160)"),
                kept);
    }

    @Test
    void readsTheSpigotLayoutToo() {
        List<String> kept = problems(
                "[12:00:00] [Server thread/INFO]: [CDisc] Enabling CDisc v2.2.2",
                "[12:00:01] [cdisc-yt-resolve/WARN]: [CDisc] No source could load this track.");
        assertEquals(List.of("[12:00:01] [cdisc-yt-resolve/WARN]: [CDisc] No source could load this track."), kept);
    }

    @Test
    void repeatsCollapseIntoTheLastOne() {
        List<String> kept = problems(
                "[12:00:00 WARN]: [CDisc] Same thing",
                "[12:00:01 WARN]: [CDisc] Other thing",
                "[12:00:02 WARN]: [CDisc] Same thing");
        assertEquals(List.of(
                "[12:00:01 WARN]: [CDisc] Other thing",
                "",
                "[12:00:02 WARN]: [CDisc] Same thing",
                "(2 times in this log, the last one shown)"), kept);
    }

    @Test
    void aLongTraceIsCut() {
        List<String> log = new ArrayList<>();
        log.add("[12:00:00 ERROR]: [CDisc] Deep");
        for (int i = 0; i < 500; i++) log.add("\tat dev.valkdz.cdisc.Deep.call(Deep.java:" + i + ")");
        List<String> kept = LogUpload.problems(log.iterator());
        assertTrue(kept.size() <= 82, String.valueOf(kept.size()));
        assertTrue(kept.get(kept.size() - 1).contains("more lines"));
    }

    @Test
    void aYamlErrorDoesNotQuoteTheToken() {
        String error = String.join("\n",
                "[18:43:09] [Server thread/ERROR]: Cannot load plugins\\CDisc\\tokens.yml",
                " in 'reader', line 56, column 5:",
                "        po-token: \"MlWBrXVXIuZqPIGTz6zEC ... ",
                "        ^",
                "\tat dev.valkdz.cdisc.util.Tokens.reload(Tokens.java:67)");
        String hidden = LogUpload.hideTokens(error);
        assertFalse(hidden.contains("MlWBrXVXIuZqPIGTz6zEC"), hidden);
        assertTrue(hidden.contains("po-token: [hidden by CDisc]\n        ^"), hidden);
        assertTrue(hidden.contains("line 56, column 5"), hidden);
    }

    @Test
    void aQuietLogUploadsNothing() {
        assertTrue(problems("[12:00:00 INFO]: [CDisc] Enabling CDisc v2.2.2", "[12:00:01 WARN]: Can't keep up!").isEmpty());
        assertFalse(problems("[12:00:01 WARN]: [CDisc] x").isEmpty());
    }
}
