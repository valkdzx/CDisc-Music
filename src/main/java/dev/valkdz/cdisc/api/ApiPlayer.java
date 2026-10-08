package dev.valkdz.cdisc.api;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.config.PlayerPrefs;
import dev.valkdz.cdisc.feature.lyrics.LyricsMode;
import dev.valkdz.cdisc.feature.lyrics.LyricsPrefs;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ApiPlayer implements PlayerSettings {

    private final Main plugin;
    private final Player player;

    ApiPlayer(Main plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    private void onPlayer(Runnable work) {
        Main cdisc = Main.getInstance();
        if (cdisc == null || !cdisc.isEnabled()) throw new IllegalStateException("CDisc is not enabled");
        Tasks.onEntity(plugin, player, work);
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public String lyrics() {
        return LyricsPrefs.mode(player).key();
    }

    @Override
    public void lyrics(String mode) {
        LyricsMode chosen = null;
        for (LyricsMode candidate : LyricsMode.values()) {
            if (candidate.key().equalsIgnoreCase(mode == null ? "" : mode.trim())) chosen = candidate;
        }
        if (chosen == null) {
            throw new IllegalArgumentException("unknown lyrics mode " + mode + "; use one of " + lyricsModes());
        }
        LyricsMode picked = chosen;
        onPlayer(() -> plugin.getPlayerActions().chooseLyrics(player, picked));
    }

    @Override
    public List<String> lyricsModes() {
        List<String> out = new ArrayList<>();
        for (LyricsMode mode : LyricsMode.values()) out.add(mode.key().toLowerCase(Locale.ROOT));
        return out;
    }

    @Override
    public boolean trackMessages() {
        return PlayerPrefs.showsTrackMessages(player);
    }

    @Override
    public void trackMessages(boolean on) {
        onPlayer(() -> PlayerPrefs.setTrackMessages(player, on));
    }

    @Override
    public boolean lyricsScoreboard() {
        return PlayerPrefs.showsLyricsScoreboard(player);
    }

    @Override
    public void lyricsScoreboard(boolean on) {
        onPlayer(() -> PlayerPrefs.setLyricsScoreboard(player, on));
    }
}
