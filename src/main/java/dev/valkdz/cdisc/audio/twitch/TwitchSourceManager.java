package dev.valkdz.cdisc.audio.twitch;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import java.io.DataInput;

public final class TwitchSourceManager extends TwitchStreamAudioSourceManager {

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        AudioItem item = super.loadItem(manager, reference);

        return item instanceof TwitchStreamAudioTrack track
                ? new TwitchStreamTrack(track.getInfo(), this, track.getChannelName())
                : item;
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
        return new TwitchStreamTrack(trackInfo, this,
                getChannelIdentifierFromUrl(trackInfo.identifier));
    }
}
