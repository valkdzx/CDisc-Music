package dev.valkdz.cdisc.api;

public record DiscInfo(String source, String title, String author, long lengthMs, boolean live, boolean locked) {
}
