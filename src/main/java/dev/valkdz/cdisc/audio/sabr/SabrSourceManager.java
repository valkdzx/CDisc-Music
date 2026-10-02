package dev.valkdz.cdisc.audio.sabr;

import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;

public final class SabrSourceManager implements AudioSourceManager {

    public static final String NAME = "youtube-sabr";

    private final String name;

    public SabrSourceManager() {
        this(NAME);
    }

    public SabrSourceManager(String name) {
        this.name = name;
    }

    @Override
    public String getSourceName() {
        return name;
    }

    @Override
    public AudioItem loadItem(String identifier) {
        return null;
    }
}
