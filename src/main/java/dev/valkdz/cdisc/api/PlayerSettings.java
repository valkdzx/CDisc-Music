package dev.valkdz.cdisc.api;

import org.bukkit.entity.Player;

import java.util.List;

public interface PlayerSettings {

    Player player();

    String lyrics();

    void lyrics(String mode);

    List<String> lyricsModes();

    boolean trackMessages();

    void trackMessages(boolean on);

    boolean lyricsScoreboard();

    void lyricsScoreboard(boolean on);
}
