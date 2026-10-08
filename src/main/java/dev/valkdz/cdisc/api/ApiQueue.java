package dev.valkdz.cdisc.api;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.disc.ItemUtils;
import dev.valkdz.cdisc.jukebox.PlaybackManager;
import dev.valkdz.cdisc.jukebox.queue.DiscQueue;
import dev.valkdz.cdisc.jukebox.queue.PlayedPolicy;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class ApiQueue implements QueueControl {

    private final Main plugin;
    private final Block block;

    ApiQueue(Main plugin, Block block) {
        this.plugin = plugin;
        this.block = block;
    }

    private static PlaybackManager apm() {
        PlaybackManager apm = CDiscApi.manager();
        if (apm == null) throw new QueueException(QueueException.Reason.CDISC_DISABLED, "CDisc is not enabled");
        return apm;
    }

    // Only the thread that owns the block may read its state, which a saved queue is restored from.
    private DiscQueue readable() {
        PlaybackManager apm = apm();
        return Tasks.owns(block.getLocation()) ? apm.getQueue(block) : apm.liveQueue(block);
    }

    private <T> CompletableFuture<T> onBlock(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Tasks.inRegion(plugin, block, () -> {
                try {
                    result.complete(work.get());
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private void requireJukebox() {
        if (block.getType() != Material.JUKEBOX) {
            throw new QueueException(QueueException.Reason.NOT_JUKEBOX, "The jukebox is gone");
        }
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= DiscQueue.CAPACITY) {
            throw new QueueException(QueueException.Reason.BAD_SLOT,
                    "Slot " + slot + " is outside 0-" + (DiscQueue.CAPACITY - 1));
        }
    }

    private void refresh() {
        if (plugin.getQueueGuiManager() != null) plugin.getQueueGuiManager().refreshOpen(block);
    }

    private void drop(ItemStack disc) {
        if (disc == null || ItemUtils.isLocked(disc)) return;
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.1, 0.5), disc);
    }

    private static QueueEntry entryOf(DiscQueue queue, int slot) {
        ItemStack disc = queue.getSlot(slot);
        if (disc == null) return null;
        ItemUtils.DiscData data = ItemUtils.readDiscData(disc);
        ItemUtils.Hint hint = data == null ? null : data.hint();
        return new QueueEntry(slot,
                data == null ? null : data.query(),
                data == null ? null : data.title(),
                data == null ? null : data.author(),
                hint == null ? 0 : hint.lengthMs(),
                hint != null && hint.live(),
                slot == queue.getCurrentIndex(),
                ItemUtils.isLocked(disc));
    }

    @Override
    public Block block() {
        return block;
    }

    @Override
    public int capacity() {
        return DiscQueue.CAPACITY;
    }

    @Override
    public int size() {
        DiscQueue queue = readable();
        return queue == null ? 0 : queue.filledCount();
    }

    @Override
    public List<QueueEntry> entries() {
        DiscQueue queue = readable();
        if (queue == null) return List.of();
        List<QueueEntry> out = new ArrayList<>();
        for (int slot = 0; slot < DiscQueue.CAPACITY; slot++) {
            QueueEntry entry = entryOf(queue, slot);
            if (entry != null) out.add(entry);
        }
        return out;
    }

    @Override
    public Optional<QueueEntry> entry(int slot) {
        checkSlot(slot);
        DiscQueue queue = readable();
        return queue == null ? Optional.empty() : Optional.ofNullable(entryOf(queue, slot));
    }

    @Override
    public int currentSlot() {
        DiscQueue queue = readable();
        return queue == null ? -1 : queue.getCurrentIndex();
    }

    @Override
    public boolean addsRealDiscs() {
        return plugin.cdiscConfig().isApiDiscsReal();
    }

    @Override
    public CompletableFuture<List<QueueEntry>> add(String source) {
        return add(source, null, null);
    }

    @Override
    public CompletableFuture<List<QueueEntry>> add(String source, String title, String author) {
        apm();
        boolean locked = !plugin.cdiscConfig().isApiDiscsReal();
        Material material = ApiDiscs.material(plugin);
        return ApiDiscs.resolve(source, title, author, DiscQueue.CAPACITY).thenCompose(tracks -> onBlock(() -> {
            requireJukebox();
            DiscQueue queue = apm().getOrCreateQueue(block);
            List<QueueEntry> added = new ArrayList<>();
            for (ApiDiscs.Track track : tracks) {
                int slot = queue.firstEmpty();
                if (slot < 0) break;
                queue.setSlot(slot, ApiDiscs.item(track, material, locked));
                added.add(entryOf(queue, slot));
            }
            if (added.isEmpty()) {
                throw new QueueException(QueueException.Reason.FULL,
                        "The queue is full (" + DiscQueue.CAPACITY + ")");
            }
            refresh();
            return added;
        }));
    }

    @Override
    public CompletableFuture<Boolean> remove(int slot) {
        checkSlot(slot);
        apm();
        return onBlock(() -> {
            PlaybackManager apm = apm();
            DiscQueue queue = apm.getQueue(block);
            if (queue == null || queue.getSlot(slot) == null) return false;

            if (slot == queue.getCurrentIndex()) leaveCurrent(apm, queue, queue.filledCount() > 1);
            ItemStack disc = queue.getSlot(slot);
            queue.setSlot(slot, null);
            drop(disc);
            refresh();
            return true;
        });
    }

    // A playing entry hands over to the next one first, so taking it out does not cut the music.
    private void leaveCurrent(PlaybackManager apm, DiscQueue queue, boolean playNext) {
        int current = queue.getCurrentIndex();
        if (apm.hasActiveSession(block)) {
            if (playNext) {
                apm.skipToNext(block);
                if (queue.getCurrentIndex() != current) return;
            }
            apm.stopPlaying(block, apm.getGeneration(block));
        }
        ItemStack disc = queue.getSlot(current);
        if (disc != null) {
            apm.releaseCurrentDisc(block, disc);
        } else {
            queue.setCurrentIndex(-1);
        }
    }

    @Override
    public CompletableFuture<Boolean> move(int from, int to) {
        checkSlot(from);
        checkSlot(to);
        apm();
        return onBlock(() -> {
            DiscQueue queue = apm().getQueue(block);
            if (queue == null || queue.getSlot(from) == null) return false;
            if (from == to) return true;

            ItemStack moving = queue.getSlot(from);
            ItemStack other = queue.getSlot(to);
            int current = queue.getCurrentIndex();
            queue.setSlot(to, moving);
            queue.setSlot(from, other);
            if (current == from) {
                queue.setCurrentIndex(to);
            } else if (current == to) {
                queue.setCurrentIndex(from);
            }
            refresh();
            return true;
        });
    }

    @Override
    public CompletableFuture<Integer> clear() {
        apm();
        return onBlock(() -> {
            PlaybackManager apm = apm();
            DiscQueue queue = apm.getQueue(block);
            if (queue == null) return 0;

            if (queue.getCurrentIndex() >= 0) leaveCurrent(apm, queue, false);
            int removed = 0;
            for (int slot = 0; slot < DiscQueue.CAPACITY; slot++) {
                ItemStack disc = queue.getSlot(slot);
                if (disc == null) continue;
                queue.setSlot(slot, null);
                drop(disc);
                removed++;
            }
            queue.setCurrentIndex(-1);
            refresh();
            return removed;
        });
    }

    @Override
    public void play(int slot) {
        checkSlot(slot);
        PlaybackManager apm = apm();
        onBlock(() -> {
            requireJukebox();
            DiscQueue queue = apm.getQueue(block);
            if (queue == null || queue.getSlot(slot) == null) {
                throw new QueueException(QueueException.Reason.BAD_SLOT, "Slot " + slot + " is empty");
            }
            apm.playQueueEntry(block, slot);
            return null;
        });
    }

    @Override
    public AfterPlay afterPlay() {
        DiscQueue queue = readable();
        PlayedPolicy policy = queue == null ? PlayedPolicy.NOTHING : queue.getPolicy();
        return switch (policy) {
            case NOTHING -> AfterPlay.KEEP;
            case EJECT -> AfterPlay.EJECT;
            case MOVE_TO_END -> AfterPlay.MOVE_TO_END;
        };
    }

    @Override
    public void afterPlay(AfterPlay policy) {
        PlayedPolicy mapped = switch (policy) {
            case KEEP -> PlayedPolicy.NOTHING;
            case EJECT -> PlayedPolicy.EJECT;
            case MOVE_TO_END -> PlayedPolicy.MOVE_TO_END;
        };
        apm();
        onBlock(() -> {
            apm().getOrCreateQueue(block).setPolicy(mapped);
            refresh();
            return null;
        });
    }

    @Override
    public boolean crossfade() {
        DiscQueue queue = readable();
        return queue == null || queue.isCrossfade();
    }

    @Override
    public void crossfade(boolean on) {
        apm();
        onBlock(() -> {
            DiscQueue queue = apm().getOrCreateQueue(block);
            if (queue.isCrossfade() != on) queue.setCrossfade(on);
            return null;
        });
    }
}
