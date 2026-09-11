package dev.valkdz.cdisc.audio.queue;

public enum RepeatMode {

    OFF,

    QUEUE,

    TRACK;

    public RepeatMode next() {
        return switch (this) {
            case OFF -> QUEUE;
            case QUEUE -> TRACK;
            case TRACK -> OFF;
        };
    }
}
