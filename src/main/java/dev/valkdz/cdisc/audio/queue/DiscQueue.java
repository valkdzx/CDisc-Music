package dev.valkdz.cdisc.audio.queue;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class DiscQueue {

    public static final int CAPACITY = 42;

    private final ItemStack[] slots = new ItemStack[CAPACITY];
    private int currentIndex = -1;
    private PlayedPolicy policy = PlayedPolicy.NOTHING;

    private int revision;

    public int getRevision() {
        return revision;
    }

    public ItemStack getSlot(int index) {
        if (index < 0 || index >= CAPACITY) return null;
        return slots[index];
    }

    public void setSlot(int index, ItemStack item) {
        if (index < 0 || index >= CAPACITY) return;
        slots[index] = (item == null || item.getType().isAir()) ? null : item;
        revision++;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int index) {
        this.currentIndex = index;
        revision++;
    }

    public ItemStack getCurrent() {
        return getSlot(currentIndex);
    }

    public PlayedPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(PlayedPolicy policy) {
        this.policy = policy;
        revision++;
    }

    public PlayedPolicy cyclePolicy() {
        this.policy = policy.next();
        revision++;
        return policy;
    }

    public boolean isEmpty() {
        for (ItemStack slot : slots) {
            if (slot != null) return false;
        }
        return true;
    }

    public int firstFilled() {
        for (int i = 0; i < CAPACITY; i++) {
            if (slots[i] != null) return i;
        }
        return -1;
    }

    public int nextFilledAfter(int from) {
        for (int i = Math.max(from, -1) + 1; i < CAPACITY; i++) {
            if (slots[i] != null) return i;
        }
        return -1;
    }

    public int prevFilledBefore(int from) {
        int start = Math.min(from, CAPACITY) - 1;
        for (int i = start; i >= 0; i--) {
            if (slots[i] != null) return i;
        }
        return -1;
    }

    public int filledCount() {
        int n = 0;
        for (ItemStack slot : slots) {
            if (slot != null) n++;
        }
        return n;
    }

    public int lastFilled() {
        for (int i = CAPACITY - 1; i >= 0; i--) {
            if (slots[i] != null) return i;
        }
        return -1;
    }

    public int firstEmpty() {
        for (int i = 0; i < CAPACITY; i++) {
            if (slots[i] == null) return i;
        }
        return -1;
    }

    public int moveToEnd(int index) {
        ItemStack disc = getSlot(index);
        if (disc == null) return index;

        slots[index] = null;
        int target = lastFilled() + 1;
        if (target >= CAPACITY || target < 0) {

            slots[index] = disc;
            return index;
        }
        slots[target] = disc;
        revision++;
        return target;
    }

    public List<ItemStack> drainAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            if (slots[i] != null) {
                out.add(slots[i]);
                slots[i] = null;
            }
        }
        currentIndex = -1;
        revision++;
        return out;
    }

    public List<ItemStack> drainAllExcept(int discardIndex) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            if (slots[i] != null && i != discardIndex) {
                out.add(slots[i]);
            }
            slots[i] = null;
        }
        currentIndex = -1;
        revision++;
        return out;
    }
}
