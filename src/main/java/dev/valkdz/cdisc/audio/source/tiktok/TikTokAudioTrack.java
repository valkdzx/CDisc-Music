package dev.valkdz.cdisc.audio.source.tiktok;

import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.HttpStream;
import dev.valkdz.cdisc.audio.player.Playback;

public final class TikTokAudioTrack extends AudioTrack {

    private final TikTokSourceManager sourceManager;

    public TikTokAudioTrack(AudioTrackInfo trackInfo, TikTokSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
    }

    // Media addresses expire within a day, so a queued or restored track resolves afresh on every play.
    @Override
    public void process(Playback playback) throws Exception {
        TikTokItem item = sourceManager.reader().read(trackInfo.identifier);
        playback.decode(new HttpStream(item.mediaUrl(), -1, "User-Agent", TikTokReader.USER_AGENT), item.mimeType());
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new TikTokAudioTrack(trackInfo, sourceManager);
    }
}
