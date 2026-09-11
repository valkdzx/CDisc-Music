package dev.valkdz.cdisc.audio.twitch;

import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamSegmentUrlProvider;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;

import java.io.IOException;

final class TwitchSegments extends TwitchStreamSegmentUrlProvider {

    TwitchSegments(String channelName, TwitchStreamAudioSourceManager manager) {
        super(channelName, manager);
    }

    String playlistUrl(HttpInterface httpInterface) throws IOException {
        return fetchSegmentPlaylistUrl(httpInterface);
    }
}
