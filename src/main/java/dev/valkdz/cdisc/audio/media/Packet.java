package dev.valkdz.cdisc.audio.media;

public record Packet(byte[] data, int offset, int length, long timeUs) {

    public static final long UNKNOWN_TIME = Long.MIN_VALUE;

    public Packet(byte[] data, long timeUs) {
        this(data, 0, data.length, timeUs);
    }
}
