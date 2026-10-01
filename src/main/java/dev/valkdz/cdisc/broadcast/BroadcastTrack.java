package dev.valkdz.cdisc.broadcast;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.util.Locale;

public final class BroadcastTrack extends BaseAudioTrack {

    public static final String PREFIX = "broadcast:";
    public static final int MAX_NAME = 32;

    public BroadcastTrack(String address, String title, String author) {
        this(new AudioTrackInfo(title != null ? title : nameOf(address), author != null ? author : "",
                Long.MAX_VALUE, address, true, address));
    }

    private BroadcastTrack(AudioTrackInfo info) {
        super(info);
    }

    public static boolean isAddress(String query) {
        return query != null && query.trim().toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    public static String nameOf(String address) {
        if (!isAddress(address)) return null;
        String name = address.trim().substring(PREFIX.length()).replace("&", "").replace("§", "").trim();
        return name.isEmpty() || name.length() > MAX_NAME ? null : name;
    }

    // The voices go out through microphone sources of their own; this track only keeps
    // the jukebox's session alive and reads as a live stream everywhere else.
    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        executor.executeProcessingLoop(() -> {
            while (true) Thread.sleep(1000L);
        }, null);
    }

    @Override
    public boolean isSeekable() {
        return false;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new BroadcastTrack(getInfo());
    }
}
