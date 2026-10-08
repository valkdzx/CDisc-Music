package dev.valkdz.cdisc.api;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public interface GuiControl {

    enum Screen {
        PLAYER(true),
        ADVANCED(true),
        QUEUE(true),
        SPEAKERS(true),
        BROADCAST(true),
        LYRICS_LOOK(false),
        SETTINGS(false);

        private final boolean needsJukebox;

        Screen(boolean needsJukebox) {
            this.needsJukebox = needsJukebox;
        }

        public boolean needsJukebox() {
            return needsJukebox;
        }
    }

    void open(Player player, Screen screen, Block jukebox);

    void open(Player player, Screen screen);

    void openPlaylist(Player player, String source);

    boolean isOpen(Player player);

    void close(Player player);

    void closeAll(Block jukebox);
}
