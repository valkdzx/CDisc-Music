package dev.valkdz.cdisc.audio.player;

public abstract class AudioTrack implements AudioItem {

    protected final AudioTrackInfo trackInfo;
    private volatile Object userData;
    private volatile long startPosition;
    private volatile TrackExecutor executor;

    protected AudioTrack(AudioTrackInfo trackInfo) {
        this.trackInfo = trackInfo;
    }

    public AudioTrackInfo getInfo() {
        return trackInfo;
    }

    public String getIdentifier() {
        return trackInfo.identifier;
    }

    public long getDuration() {
        return trackInfo.length;
    }

    public boolean isSeekable() {
        return !trackInfo.isStream;
    }

    public long getPosition() {
        TrackExecutor running = executor;
        return running != null ? running.position() : startPosition;
    }

    public void setPosition(long positionMs) {
        if (!isSeekable()) return;
        TrackExecutor running = executor;
        if (running != null) {
            running.seek(Math.max(0, positionMs));
        } else {
            startPosition = Math.max(0, positionMs);
        }
    }

    long startPosition() {
        return startPosition;
    }

    void bind(TrackExecutor executor) {
        this.executor = executor;
    }

    public Object getUserData() {
        return userData;
    }

    public void setUserData(Object userData) {
        this.userData = userData;
    }

    public AudioSourceManager getSourceManager() {
        return null;
    }

    public AudioTrack makeClone() {
        AudioTrack clone = makeShallowClone();
        clone.userData = userData;
        return clone;
    }

    protected abstract AudioTrack makeShallowClone();

    // Runs on the track's own thread; returns when the audio ends.
    public abstract void process(Playback playback) throws Exception;
}
