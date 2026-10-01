package dev.valkdz.cdisc.api;

import org.bukkit.block.Block;

public record NowPlaying(Block jukebox, String title, String author, String uri,
                         long positionMs, long durationMs, boolean paused, boolean live) {
}
