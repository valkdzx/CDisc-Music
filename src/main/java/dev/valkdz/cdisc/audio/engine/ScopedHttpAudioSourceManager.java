package dev.valkdz.cdisc.audio.engine;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import dev.valkdz.cdisc.util.SafeUrl;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.protocol.HttpClientContext;

import java.io.IOException;
import java.util.Locale;

final class ScopedHttpAudioSourceManager extends HttpAudioSourceManager {

    static final String REFUSED = "Links into this server's own network are refused.";

    // Runs before every connection, redirects and later reconnects of a playing track included.
    static final HttpRequestInterceptor PUBLIC_ONLY = (request, context) -> {
        HttpHost target = HttpClientContext.adapt(context).getTargetHost();
        if (target != null && SafeUrl.judgeHost(target.getHostName()) == SafeUrl.Verdict.PRIVATE_ADDRESS) {
            throw new IOException(REFUSED + " (" + target.getHostName() + ")");
        }
    };

    private final String[] allowedPrefixes;

    ScopedHttpAudioSourceManager(String... allowedPrefixes) {
        this.allowedPrefixes = new String[allowedPrefixes.length];
        for (int i = 0; i < allowedPrefixes.length; i++) {
            this.allowedPrefixes[i] = allowedPrefixes[i].toLowerCase(Locale.ROOT);
        }
        configureBuilder(builder -> builder.addInterceptorFirst(PUBLIC_ONLY));
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        String identifier = reference == null ? null : reference.identifier;
        if (identifier == null || !allowed(identifier.toLowerCase(Locale.ROOT))) return null;

        if (SafeUrl.judge(identifier) == SafeUrl.Verdict.PRIVATE_ADDRESS) {
            throw new FriendlyException(REFUSED, FriendlyException.Severity.COMMON, null);
        }
        return super.loadItem(manager, reference);
    }

    private boolean allowed(String lower) {
        if (allowedPrefixes.length == 0) return lower.startsWith("http://") || lower.startsWith("https://");
        for (String prefix : allowedPrefixes) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }
}
