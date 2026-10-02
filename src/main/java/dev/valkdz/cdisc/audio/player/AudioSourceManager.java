package dev.valkdz.cdisc.audio.player;

public interface AudioSourceManager {

    String getSourceName();

    // Returns null when the identifier is not this source's, AudioItem.NONE when it is but holds nothing.
    AudioItem loadItem(String identifier);

    default void shutdown() {
    }
}
