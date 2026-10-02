package dev.valkdz.cdisc.audio.source.tiktok;

import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.LoadException;

import java.io.IOException;

public final class TikTokSourceManager implements AudioSourceManager {

    private final TikTokReader reader = new TikTokReader();

    @Override
    public String getSourceName() {
        return "tiktok";
    }

    @Override
    public AudioItem loadItem(String identifier) {
        if (!reader.matches(identifier)) return null;

        try {
            return trackOf(reader.read(identifier));
        } catch (TikTokReader.TikTokException e) {
            throw new LoadException(e.getMessage(), e);
        } catch (IOException e) {
            throw new LoadException("Could not read the TikTok video", e);
        }
    }

    public AudioTrack trackOf(TikTokItem item) {
        return new TikTokAudioTrack(new AudioTrackInfo(
                item.title(),
                item.author() == null ? "TikTok" : item.author(),
                item.durationMs(),
                item.pageUrl(),
                false,
                item.pageUrl(),
                item.artworkUrl(),
                null), this);
    }

    public TikTokReader reader() {
        return reader;
    }
}
