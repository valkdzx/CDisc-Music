package dev.valkdz.cdisc.lyrics.chat;

import dev.valkdz.cdisc.lyrics.LyricsRenderer;
import dev.valkdz.cdisc.lyrics.LyricsStyle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveChatTest {

    private static final char S = '§';

    @Test
    void readsTwitchAndYouTubeLinks() {
        assertEquals("twitch:xqc", LiveChat.key("https://www.twitch.tv/xQc"));
        assertEquals("youtube:99DStHPFfaE", LiveChat.key("https://www.youtube.com/watch?v=99DStHPFfaE"));
        assertEquals("youtube:99DStHPFfaE", LiveChat.key("https://youtu.be/99DStHPFfaE?t=3"));
        assertEquals("youtube:99DStHPFfaE", LiveChat.key("https://www.youtube.com/live/99DStHPFfaE"));
        assertNull(LiveChat.key("https://soundcloud.com/someone/live"));
        assertNull(LiveChat.key(null));
    }

    @Test
    void twitchNamesKeepTheirColour() {
        assertEquals(S + "x" + S + "1" + S + "e" + S + "9" + S + "0" + S + "f" + S + "f",
                TwitchChat.colorOf("#1E90FF", "someone"));
        assertEquals(TwitchChat.colorOf("#00FF7F", "a"), TwitchChat.colorOf("", "a"));
    }

    @Test
    void messagesCannotCarryColourCodes() {
        ChatMessage message = ChatMessage.of(S + "cName", null, "hi " + S + "lthere", 0);
        assertEquals("cName", message.author());
        assertEquals("hi lthere", message.text());
        assertNull(ChatMessage.of("Name", null, "   ", 0));
        assertEquals("abc…", ChatMessage.of("Name", null, "abcdef", 3).text());
    }

    @Test
    void newestMessagesFillTheWindow() {
        List<ChatMessage> chat = List.of(
                ChatMessage.of("one", null, "first", 0),
                ChatMessage.of("two", S + "a", "second", 0),
                ChatMessage.of("three", null, "third", 0));
        LyricsRenderer.Options options = new LyricsRenderer.Options(1, 0,
                LyricsStyle.of(S + "f", S + "8"), 0, "●", "○", 1f);

        assertEquals(List.of(
                S + "a[two]: " + S + "f" + "second",
                S + "8[three]: " + LyricsStyle.of(S + "f", S + "8").rising(1f) + "third"),
                LyricsRenderer.chat(chat, options, 255));
    }
}
