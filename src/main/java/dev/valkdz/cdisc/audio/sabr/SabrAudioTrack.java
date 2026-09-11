package dev.valkdz.cdisc.audio.sabr;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerHints;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.util.function.Supplier;

public final class SabrAudioTrack extends DelegatedAudioTrack {

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
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        try (SabrSeekableInputStream stream = streamOpener.get()) {
            MediaContainerDescriptor container = detect(stream);

            stream.seek(0);

            processDelegate((InternalAudioTrack) container.createTrack(trackInfo, stream), executor);

            String truncation = stream.truncation();
            if (truncation != null) {
                org.bukkit.Bukkit.getLogger().warning("[CDisc] " + truncation);
            }

            if (stream.notAttested()) {
                dev.valkdz.cdisc.youtube.PoTokenService.reportNotAttested();
            }
        }
    }

    private MediaContainerDescriptor detect(SabrSeekableInputStream stream) {
        MediaContainerDetectionResult result = new MediaContainerDetection(
                MediaContainerRegistry.DEFAULT_REGISTRY,
                new AudioReference(trackInfo.identifier, trackInfo.title),
                stream,
                MediaContainerHints.from(baseMimeType(), null)).detectContainer();

        if (!result.isContainerDetected()) {
            throw new FriendlyException("The SABR stream is in no format we recognise",
                    FriendlyException.Severity.SUSPICIOUS, null);
        }
        if (!result.isSupportedFile()) {
            throw new FriendlyException("The SABR stream is in a format we cannot play: "
                    + result.getUnsupportedReason(), FriendlyException.Severity.COMMON, null);
        }
        return result.getContainerDescriptor();
    }

    private String baseMimeType() {
        if (mimeType == null) return null;

        int parameters = mimeType.indexOf(';');
        return parameters < 0 ? mimeType.trim() : mimeType.substring(0, parameters).trim();
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
