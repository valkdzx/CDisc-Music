package dev.valkdz.cdisc.audio.queue;

public enum PlayedPolicy {

    NOTHING,

    EJECT,

    MOVE_TO_END;

    public PlayedPolicy next() {
        return switch (this) {
            case NOTHING -> EJECT;
            case EJECT -> MOVE_TO_END;
            case MOVE_TO_END -> NOTHING;
        };
    }
}
