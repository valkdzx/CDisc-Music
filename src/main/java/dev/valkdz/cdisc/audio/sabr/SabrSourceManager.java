package dev.valkdz.cdisc.audio.sabr;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import java.io.DataInput;
import java.io.DataOutput;

public final class SabrSourceManager implements AudioSourceManager {

    public static final String NAME = "youtube-sabr";

    @Override
    public String getSourceName() {
        return NAME;
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        return null;
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return false;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
        throw new UnsupportedOperationException("A SABR track is bound to a session and cannot be stored");
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo info, DataInput input) {
        throw new UnsupportedOperationException("A SABR track is bound to a session and cannot be restored");
    }

    @Override
    public void shutdown() {
    }
}
