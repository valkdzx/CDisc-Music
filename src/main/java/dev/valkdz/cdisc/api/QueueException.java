package dev.valkdz.cdisc.api;

public class QueueException extends RuntimeException {

    public enum Reason {
        CDISC_DISABLED,
        NOT_JUKEBOX,
        FULL,
        NO_MATCH,
        FAILED,
        BAD_SLOT
    }

    private final Reason reason;

    public QueueException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public QueueException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
