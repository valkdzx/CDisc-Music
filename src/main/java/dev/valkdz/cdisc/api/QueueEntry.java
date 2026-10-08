package dev.valkdz.cdisc.api;

public record QueueEntry(int slot, String source, String title, String author, long lengthMs, boolean live,
                         boolean current, boolean locked) {
}
