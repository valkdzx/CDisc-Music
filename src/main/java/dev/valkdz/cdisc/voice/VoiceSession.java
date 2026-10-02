package dev.valkdz.cdisc.voice;

public interface VoiceSession {

    void sendFrame(byte[] pcm);

    boolean isClosed();

    void close();

    void setDistance(float distance);

    default void setPrivateListener(java.util.UUID listener) {
    }

    default void setSynced(boolean synced) {
    }

    default void setDirectVolume(int volume) {
    }

    default void applySpeakerSettings(dev.valkdz.cdisc.feature.speaker.SpeakerSettings settings) {
    }

    default void setSilencedListeners(java.util.Set<java.util.UUID> silenced) {
    }

    default void setExcludedListeners(java.util.Set<java.util.UUID> excluded) {
    }
}
