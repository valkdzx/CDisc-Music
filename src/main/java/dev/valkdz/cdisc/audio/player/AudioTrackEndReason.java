package dev.valkdz.cdisc.audio.player;

public enum AudioTrackEndReason {
    FINISHED(true),
    LOAD_FAILED(true),
    STOPPED(false),
    REPLACED(false),
    CLEANUP(false);

    public final boolean mayStartNext;

    AudioTrackEndReason(boolean mayStartNext) {
        this.mayStartNext = mayStartNext;
    }
}
