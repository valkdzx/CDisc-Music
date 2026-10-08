package dev.valkdz.cdisc.api;

import org.bukkit.block.Block;

import java.util.Optional;

public interface JukeboxControl {

    enum Repeat {
        OFF,
        QUEUE,
        TRACK
    }

    Block block();

    boolean isActive();

    boolean isPaused();

    Optional<NowPlaying> nowPlaying();

    int queueSize();

    QueueControl queue();

    void play(String source);

    void resumeQueue();

    void stop();

    void pause();

    void resume();

    void skip();

    void previous();

    void seek(long positionMs);

    Repeat repeat();

    void repeat(Repeat mode);

    boolean shuffle();

    void shuffle(boolean on);

    int volume();

    void volume(int volume);
}
