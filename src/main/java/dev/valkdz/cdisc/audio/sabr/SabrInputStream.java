package dev.valkdz.cdisc.audio.sabr;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class SabrInputStream extends InputStream {

    private static final int MAX_STALLS = 12;

    private static final int MAX_REDIRECTS = 5;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final SabrMessages.RequestState state;
    private final long durationMs;
    private final String userAgent;

    private final UnaryOperator<String> prepareUrl;

    private int backoffMs;

    private int protectionStatus = SabrMessages.PROTECTION_STATUS_OK;

    private String url;
    private int requestNumber;
    private int stalls;
    private boolean finished;

    private final Deque<byte[]> pending = new ArrayDeque<>();
    private byte[] current;
    private int currentPos;

    public SabrInputStream(HttpClient http, String url, long durationMs,
                           SabrMessages.RequestState state, String userAgent,
                           UnaryOperator<String> prepareUrl) {
        this.http = http;
        this.prepareUrl = prepareUrl == null ? UnaryOperator.identity() : prepareUrl;
        this.url = this.prepareUrl.apply(url);
        this.durationMs = durationMs;
        this.state = state;
        this.userAgent = userAgent;
    }

    @Override
    public int read() throws IOException {
        if (!ensureAvailable()) return -1;
        return current[currentPos++] & 0xFF;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (!ensureAvailable()) return -1;

        int taken = Math.min(length, current.length - currentPos);
        System.arraycopy(current, currentPos, destination, offset, taken);
        currentPos += taken;
        return taken;
    }

    @Override
    public int available() {
        int total = current == null ? 0 : current.length - currentPos;
        for (byte[] chunk : pending) {
            total += chunk.length;
        }
        return total;
    }

    private boolean ensureAvailable() throws IOException {
        while (current == null || currentPos >= current.length) {
            if (!pending.isEmpty()) {
                current = pending.poll();
                currentPos = 0;
                continue;
            }
            if (finished) return false;
            fetch();
        }
        return true;
    }

    private void fetch() throws IOException {
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            byte[] body = post(SabrMessages.encodeRequest(state));
            Answer answer = new Answer();
            UmpReader.forEachPart(body, answer::part);

            if (answer.error != null) {
                throw new IOException("SABR refused the stream: " + answer.error);
            }
            if (answer.blocked) {
                throw new IOException("SABR stream protection rejected this session; "
                        + "the po-token is missing, stale, or minted for another visitor id");
            }

            if (answer.redirectUrl != null && answer.received == 0) {
                // A pure redirect is not progress, so it must not count towards the stall budget.
                url = prepareUrl.apply(answer.redirectUrl);
                continue;
            }
            if (answer.redirectUrl != null) {
                url = prepareUrl.apply(answer.redirectUrl);
            }

            if (answer.endOfTrack) {
                finished = true;
                return;
            }

            if (answer.received == 0) {

                if (++stalls >= MAX_STALLS) finished = true;
                return;
            }

            stalls = 0;
            if (durationMs > 0 && state.playerTimeMs >= durationMs) finished = true;
            return;
        }
        throw new IOException("SABR redirected " + MAX_REDIRECTS + " times without sending audio");
    }

    public String truncation() {
        if (!notAttested()) return null;
        if (durationMs <= 0 || state.playerTimeMs >= durationMs - 1000) return null;

        return ("YouTube served only " + (state.playerTimeMs / 1000)
                + "s of " + (durationMs / 1000) + "s: the request was not attested "
                + "(stream protection status 2), so it was answered with a sample. "
                + "The visitor identity in use is no longer honoured for playback — "
                + "a fresh one has to be earned by a browser, which is what "
                + "youtube.po-token-backend.url is for.");
    }

    public boolean notAttested() {
        return protectionStatus == SabrMessages.PROTECTION_STATUS_LIMITED;
    }

    private void respectBackoff() throws IOException {
        if (backoffMs <= 0) return;

        int waiting = backoffMs;
        backoffMs = 0;
        try {
            Thread.sleep(waiting);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting out a SABR backoff", e);
        }
    }

    private byte[] post(byte[] payload) throws IOException {
        respectBackoff();

        String target = url + (url.indexOf('?') < 0 ? "?" : "&") + "rn=" + requestNumber++;

        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/x-protobuf")
                .header("accept", "application/vnd.yt-ump")

                .header("accept-encoding", "identity")
                .header("user-agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading a SABR stream", e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("SABR endpoint answered " + response.statusCode());
        }
        return response.body();
    }

    private final class Answer {

        private final Map<Integer, SabrMessages.MediaHeader> headers = new HashMap<>();
        private final Set<Integer> unwanted = new HashSet<>();

        private String redirectUrl;
        private SabrMessages.SabrError error;
        private boolean blocked;
        private boolean endOfTrack;
        private int received;

        void part(int type, byte[] body, int offset, int length) {
            switch (type) {
                case UmpReader.MEDIA_HEADER -> mediaHeader(body, offset, length);
                case UmpReader.MEDIA -> media(body, offset, length);
                case UmpReader.MEDIA_END -> mediaEnd(body, offset, length);
                case UmpReader.NEXT_REQUEST_POLICY -> nextRequestPolicy(body, offset, length);
                case UmpReader.SABR_REDIRECT -> redirectUrl = SabrMessages.parseRedirectUrl(body, offset, length);
                case UmpReader.SABR_CONTEXT_UPDATE -> contextUpdate(body, offset, length);
                case UmpReader.SABR_ERROR -> error = SabrMessages.SabrError.parse(body, offset, length);
                case UmpReader.END_OF_TRACK -> endOfTrack = true;
                case UmpReader.STREAM_PROTECTION_STATUS -> {
                    int status = SabrMessages.parseProtectionStatus(body, offset, length);
                    protectionStatus = status;
                    blocked = status == SabrMessages.PROTECTION_STATUS_BLOCKED;
                }

                default -> { }
            }
        }

        private void mediaHeader(byte[] body, int offset, int length) {
            SabrMessages.MediaHeader header = SabrMessages.MediaHeader.parse(body, offset, length);

            if (header.itag() != state.format.itag()) {
                unwanted.add(header.headerId());
                return;
            }
            headers.put(header.headerId(), header);

            long reachedMs = header.startMs() + header.durationMs();
            if (reachedMs > state.playerTimeMs) state.playerTimeMs = reachedMs;
            if (header.sequenceNumber() > state.lastSegmentIndex) {
                state.lastSegmentIndex = header.sequenceNumber();
            }
        }

        private void media(byte[] body, int offset, int length) {
            if (length < 1) return;

            int headerId = body[offset] & 0xFF;
            if (unwanted.contains(headerId) || !headers.containsKey(headerId)) return;

            int payload = length - 1;
            if (payload == 0) return;

            byte[] chunk = new byte[payload];
            System.arraycopy(body, offset + 1, chunk, 0, payload);
            pending.add(chunk);
            received += payload;
        }

        private void mediaEnd(byte[] body, int offset, int length) {
            if (length < 1) return;
            int headerId = body[offset] & 0xFF;
            headers.remove(headerId);
            unwanted.remove(headerId);
        }

        private void nextRequestPolicy(byte[] body, int offset, int length) {
            SabrMessages.NextRequestPolicy policy = SabrMessages.NextRequestPolicy.parse(body, offset, length);
            if (policy.playbackCookie() != null) state.playbackCookie = policy.playbackCookie();
            if (policy.backoffMs() > 0) backoffMs = policy.backoffMs();
        }

        private void contextUpdate(byte[] body, int offset, int length) {
            SabrMessages.SabrContext update = SabrMessages.SabrContext.parse(body, offset, length);
            if (update.value() == null) return;

            state.contexts.removeIf(existing -> existing.type() == update.type());
            state.contexts.add(update);
        }
    }
}
