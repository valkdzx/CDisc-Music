package dev.valkdz.cdisc.audio.sabr;

import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Playback;

import java.util.function.Supplier;

public final class SabrAudioTrack extends AudioTrack {

    private final AudioSourceManager sourceManager;
    private final Supplier<SabrSeekableInputStream> streamOpener;
    private final String mimeType;

    public SabrAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager,
                          String mimeType, Supplier<SabrSeekableInputStream> streamOpener) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.mimeType = mimeType;
        this.streamOpener = streamOpener;
    }

    @Override
    public void process(Playback playback) throws Exception {
        try (SabrSeekableInputStream stream = streamOpener.get()) {
            playback.decode(stream, mimeType);

            String truncation = stream.truncation();
            if (truncation != null) {
                org.bukkit.Bukkit.getLogger().warning("[CDisc] " + truncation);
            }

            if (stream.notAttested()) {
                dev.valkdz.cdisc.youtube.PoTokenService.reportNotAttested();
            }
        }
    }

    public SabrSeekableInputStream openStream() {
        return streamOpener.get();
    }

    public String mimeType() {
        return mimeType;
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new SabrAudioTrack(trackInfo, sourceManager, mimeType, streamOpener);
    }
}
