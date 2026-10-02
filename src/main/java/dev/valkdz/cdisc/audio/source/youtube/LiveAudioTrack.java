package dev.valkdz.cdisc.audio.source.youtube;

import dev.valkdz.cdisc.audio.media.ChainedDemuxer;
import dev.valkdz.cdisc.audio.media.MediaInput;
import dev.valkdz.cdisc.audio.media.container.Mp4Reader;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.audio.player.Playback;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LiveAudioTrack extends AudioTrack {

    private static final Pattern SEQUENCE = Pattern.compile("Sequence-Number: (\\d+)");
    private static final int LEAD_SEGMENTS = 2;
    private static final int MAX_MISSES = 30;
    private static final long MISS_WAIT_MS = 1000L;

    private final AudioSourceManager sourceManager;
    private final Supplier<String> url;

    public LiveAudioTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager, Supplier<String> url) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.url = url;
    }

    @Override
    public void process(Playback playback) throws Exception {
        // Asked for on every start, not held in a field: a live URL expires, and a dropped
        // stream is resumed by replaying this same track.
        String base = url.get();
        long[] sequence = {Math.max(0, headOf(base) - LEAD_SEGMENTS)};

        // Every segment is a whole fragmented MP4 with its own moov.
        playback.decode(new ChainedDemuxer(() -> {
            for (int misses = 0; misses < MAX_MISSES; misses++) {
                if (playback.stopped()) return null;
                HttpResponse<byte[]> response = Http.get(base + "&sq=" + sequence[0]);
                if (response.statusCode() == 200 && response.body().length > 0) {
                    sequence[0]++;
                    return new Mp4Reader(MediaInput.of(response.body()));
                }
                if (response.statusCode() == 403) throw new IOException("The live stream address expired");
                sleep();
            }
            return null;
        }));
    }

    private static long headOf(String base) throws IOException {
        HttpResponse<byte[]> response = Http.get(base);
        Http.expectOk(response, null);
        long head = response.headers().firstValueAsLong("X-Head-Seqnum").orElse(-1);
        if (head >= 0) return head;
        Matcher matcher = SEQUENCE.matcher(new String(response.body(), StandardCharsets.ISO_8859_1));
        if (matcher.find()) return Long.parseLong(matcher.group(1));
        throw new IOException("The live stream told no sequence number");
    }

    private static void sleep() throws IOException {
        try {
            Thread.sleep(MISS_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the next live segment", e);
        }
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new LiveAudioTrack(trackInfo, sourceManager, url);
    }
}
