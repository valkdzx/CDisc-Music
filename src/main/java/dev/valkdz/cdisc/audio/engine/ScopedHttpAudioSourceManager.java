package dev.valkdz.cdisc.audio.engine;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;

import java.util.Locale;

final class ScopedHttpAudioSourceManager extends HttpAudioSourceManager {

    private final String[] allowedPrefixes;

    ScopedHttpAudioSourceManager(String... allowedPrefixes) {
        this.allowedPrefixes = new String[allowedPrefixes.length];
        for (int i = 0; i < allowedPrefixes.length; i++) {
            this.allowedPrefixes[i] = allowedPrefixes[i].toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        String identifier = reference == null ? null : reference.identifier;
        if (identifier == null) return null;

        String lower = identifier.toLowerCase(Locale.ROOT);
        for (String prefix : allowedPrefixes) {
            if (lower.startsWith(prefix)) return super.loadItem(manager, reference);
        }
        return null;
    }
}
