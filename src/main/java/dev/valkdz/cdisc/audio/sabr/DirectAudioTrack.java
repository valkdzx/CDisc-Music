package dev.valkdz.cdisc.audio.sabr;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerHints;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.net.URI;

public final class DirectAudioTrack extends DelegatedAudioTrack {

    private final AudioSourceManager sourceManager;
    private final HttpInterfaceManager interfaces;
    private final String url;
    private final String mimeType;
    private final long contentLength;

    public DirectAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager,
                            HttpInterfaceManager interfaces, String url,
                            String mimeType, long contentLength) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.interfaces = interfaces;
        this.url = url;
        this.mimeType = mimeType;
        this.contentLength = contentLength;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        try (HttpInterface httpInterface = interfaces.getInterface();
             PersistentHttpStream stream = new PersistentHttpStream(
                     httpInterface, URI.create(url), contentLength > 0 ? contentLength : null)) {

            MediaContainerDescriptor container = detect(stream);

            stream.seek(0);

            processDelegate((InternalAudioTrack) container.createTrack(trackInfo, stream), executor);
        }
    }

    private MediaContainerDescriptor detect(SeekableInputStream stream) {
        MediaContainerDetectionResult result = new MediaContainerDetection(
                MediaContainerRegistry.DEFAULT_REGISTRY,
                new AudioReference(trackInfo.identifier, trackInfo.title),
                stream,
                MediaContainerHints.from(baseMimeType(), null)).detectContainer();

        if (!result.isContainerDetected()) {
            throw new FriendlyException("The stream is in no format we recognise",
                    FriendlyException.Severity.SUSPICIOUS, null);
        }
        if (!result.isSupportedFile()) {
            throw new FriendlyException("The stream is in a format we cannot play: "
                    + result.getUnsupportedReason(), FriendlyException.Severity.COMMON, null);
        }
        return result.getContainerDescriptor();
    }

    private String baseMimeType() {
        if (mimeType == null) return null;
        int semicolon = mimeType.indexOf(';');
        return semicolon < 0 ? mimeType : mimeType.substring(0, semicolon).trim();
    }

    public String url() {
        return url;
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
        return new DirectAudioTrack(trackInfo, sourceManager, interfaces, url, mimeType, contentLength);
    }
}
