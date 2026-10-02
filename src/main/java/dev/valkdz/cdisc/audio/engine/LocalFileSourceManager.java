package dev.valkdz.cdisc.audio.engine;

import dev.valkdz.cdisc.audio.media.Demuxer;
import dev.valkdz.cdisc.audio.media.Media;
import dev.valkdz.cdisc.audio.media.MediaInput;
import dev.valkdz.cdisc.audio.media.Tags;
import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.audio.player.Playback;

import java.io.File;
import java.io.IOException;

final class LocalFileSourceManager implements AudioSourceManager {

    @Override
    public String getSourceName() {
        return "local";
    }

    @Override
    public AudioItem loadItem(String identifier) {
        File file = new File(identifier);
        if (!file.isAbsolute() || !file.isFile()) return null;

        try (MediaInput input = MediaInput.of(file); Demuxer demuxer = Media.open(input, null)) {
            Tags tags = demuxer.tags();
            long duration = demuxer.durationMs();
            return new LocalTrack(new AudioTrackInfo(
                    tags.title() != null ? tags.title() : AudioTrackInfo.UNKNOWN_TITLE,
                    tags.artist() != null ? tags.artist() : AudioTrackInfo.UNKNOWN_ARTIST,
                    duration > 0 ? duration : AudioTrackInfo.UNKNOWN_LENGTH,
                    file.getAbsolutePath(), false, file.getAbsolutePath()), this);
        } catch (IOException e) {
            throw new LoadException("Could not read " + file.getName() + ": " + e.getMessage(), e);
        }
    }

    private static final class LocalTrack extends AudioTrack {
        private final LocalFileSourceManager source;

        LocalTrack(AudioTrackInfo info, LocalFileSourceManager source) {
            super(info);
            this.source = source;
        }

        @Override
        public void process(Playback playback) throws Exception {
            playback.decode(MediaInput.of(new File(trackInfo.identifier)), null);
        }

        @Override
        public AudioSourceManager getSourceManager() {
            return source;
        }

        @Override
        protected AudioTrack makeShallowClone() {
            return new LocalTrack(trackInfo, source);
        }
    }
}
