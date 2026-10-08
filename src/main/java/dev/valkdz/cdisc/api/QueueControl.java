package dev.valkdz.cdisc.api;

import org.bukkit.block.Block;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface QueueControl {

    enum AfterPlay {
        KEEP,
        EJECT,
        MOVE_TO_END
    }

    Block block();

    int capacity();

    int size();

    List<QueueEntry> entries();

    Optional<QueueEntry> entry(int slot);

    int currentSlot();

    boolean addsRealDiscs();

    CompletableFuture<List<QueueEntry>> add(String source);

    CompletableFuture<List<QueueEntry>> add(String source, String title, String author);

    CompletableFuture<Boolean> remove(int slot);

    CompletableFuture<Boolean> move(int from, int to);

    CompletableFuture<Integer> clear();

    void play(int slot);

    AfterPlay afterPlay();

    void afterPlay(AfterPlay policy);

    boolean crossfade();

    void crossfade(boolean on);
}
