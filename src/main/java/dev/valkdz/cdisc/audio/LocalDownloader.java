package dev.valkdz.cdisc.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.sabr.DirectAudioTrack;
import dev.valkdz.cdisc.audio.sabr.SabrAudioTrack;
import dev.valkdz.cdisc.audio.sabr.SabrSeekableInputStream;
import dev.valkdz.cdisc.util.SafeUrl;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public final class LocalDownloader {

    private static final int MAX_REDIRECTS = 5;

    private static final int BUFFER_BYTES = 64 * 1024;

    private static final long RANGE_BYTES = 64L * 1024 * 1024;

    private static final String RANGE_REFUSED = "416";

    public enum Status {
        OK,
        DISABLED,
        BAD_URL,
        BLOCKED_ADDRESS,
        HTTP_ERROR,
        TOO_LARGE,
        BAD_TYPE,
        NAME_TAKEN,
        NO_STREAM,
        IO_ERROR
    }

    public record Result(Status status, String detail) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    private final Main plugin;
    private final ExecutorService workers;
    private final HttpClient http;

    public LocalDownloader(Main plugin) {
        this.plugin = plugin;
        this.workers = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CDisc-Download");
            t.setDaemon(true);
            return t;
        });
        this.http = dev.valkdz.cdisc.util.NetProxy.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(15))

                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void shutdown() {
        workers.shutdownNow();
    }

    public void download(String url, String desiredName, Consumer<Result> callback) {
        run(() -> fetch(url, desiredName, null), callback);
    }

    public void download(AudioTrack track, String backendUrl, String desiredName,
                         Consumer<Result> callback) {
        run(() -> fetchTrack(track, backendUrl, desiredName), callback);
    }

    private void run(Fetch fetch, Consumer<Result> callback) {
        workers.submit(() -> {
            Result result;
            try {
                result = fetch.get();
            } catch (Exception e) {
                result = new Result(Status.IO_ERROR, describe(e));
            }
            Result finished = result;
            Tasks.global(plugin, () -> callback.accept(finished));
        });
    }

    private interface Fetch {
        Result get() throws Exception;
    }

    private record Limits(LocalMusicLibrary library, long maxBytes,
                          Set<String> allowed, int timeout) {
    }

    private Limits limits() {
        LocalMusicLibrary library = plugin.getLocalMusic();
        if (library == null || !library.isEnabled()) return null;
        if (!plugin.cdiscConfig().isDownloadEnabled()) return null;

        return new Limits(library,
                plugin.cdiscConfig().getDownloadMaxBytes(),
                plugin.cdiscConfig().getLocalExtensions(),
                plugin.cdiscConfig().getDownloadTimeoutSeconds());
    }

    private Result fetchTrack(AudioTrack track, String backendUrl, String desiredName)
            throws Exception {

        Limits limits = limits();
        if (limits == null) return new Result(Status.DISABLED, null);

        if (track instanceof DirectAudioTrack direct) {
            Result saved = fetch(direct.url(), desiredName, direct.mimeType());

            if (saved.status() != Status.HTTP_ERROR || backendUrl == null) return saved;

            Bukkit.getLogger().warning("[CDisc] The direct link answered " + saved.detail()
                    + "; asking the backend for the track instead.");
        } else if (track instanceof SabrAudioTrack sabr) {
            Result saved = fromSabr(limits, sabr, desiredName, backendUrl == null);
            if (saved != null) return saved;

        }

        if (backendUrl != null) {
            return fetch(backendUrl, desiredName, null);
        }

        String identifier = track.getInfo().identifier;
        if (isHttpSource(track) && identifier != null
                && identifier.regionMatches(true, 0, "http", 0, 4)) {
            return fetch(identifier, desiredName, null);
        }
        return new Result(Status.NO_STREAM, sourceNameOf(track));
    }

    private Result fromSabr(Limits limits, SabrAudioTrack sabr, String desiredName,
                            boolean lastChance) throws Exception {
        try (SabrSeekableInputStream stream = sabr.openStream()) {
            Result saved = save(limits, stream, -1L, sabr.mimeType(), null,
                    sabr.getInfo().identifier, desiredName);

            String truncation = stream.truncation();
            if (saved.ok() && truncation != null) {
                Bukkit.getLogger().warning("[CDisc] " + truncation);
                if (!lastChance) {
                    discard(limits, saved.detail());
                    return null;
                }
            }
            return saved;
        } catch (Exception e) {
            if (lastChance) throw e;
            Bukkit.getLogger().warning("[CDisc] SABR could not hand over the whole track ("
                    + describe(e) + "); asking the backend for it instead.");
            return null;
        }
    }

    private void discard(Limits limits, String name) {
        if (name == null) return;
        try {
            Path written = limits.library().root().resolve(name).normalize();
            if (written.startsWith(limits.library().root())) Files.deleteIfExists(written);
        } catch (IOException | RuntimeException e) {
            Bukkit.getLogger().warning("[CDisc] Could not remove the truncated download "
                    + name + ": " + describe(e));
        }
    }

    private static boolean isHttpSource(AudioTrack track) {
        return "http".equals(sourceNameOf(track));
    }

    private static String sourceNameOf(AudioTrack track) {
        return track.getSourceManager() == null ? "?" : track.getSourceManager().getSourceName();
    }

    private Result fetch(String url, String desiredName, String mimeHint)
            throws IOException, InterruptedException {

        Limits limits = limits();
        if (limits == null) return new Result(Status.DISABLED, null);

        Hop first = open(url, 0, limits.timeout(), true);

        if (first.failure() != null && RANGE_REFUSED.equals(first.failure().detail())) {
            first = open(url, 0, limits.timeout(), false);
        }
        if (first.failure() != null) return first.failure();

        try (InputStream in = wholeOf(first, limits.timeout())) {
            String contentType = first.response().headers()
                    .firstValue("content-type").orElse("");
            if (contentType.isBlank() && mimeHint != null) contentType = mimeHint;

            return save(limits, in, first.total(), contentType,
                    first.response().headers().firstValue("content-disposition").orElse(null),
                    first.url(), desiredName);
        }
    }

    private record Hop(HttpResponse<InputStream> response, String url,
                       long total, Result failure) {

        static Hop failed(Result result) {
            return new Hop(null, null, -1, result);
        }
    }

    private Hop open(String url, long from, int timeout, boolean ranged)
            throws IOException, InterruptedException {

        String current = url == null ? "" : url.trim();

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            SafeUrl.Verdict verdict = SafeUrl.judge(current);
            if (verdict != SafeUrl.Verdict.OK) {
                return Hop.failed(new Result(
                        verdict == SafeUrl.Verdict.PRIVATE_ADDRESS
                                ? Status.BLOCKED_ADDRESS : Status.BAD_URL,
                        current));
            }

            HttpRequest request;
            try {
                HttpRequest.Builder building = HttpRequest.newBuilder(URI.create(current))
                        .header("User-Agent", "CDisc/" + plugin.getDescription().getVersion())
                        .header("Accept", "*/*")
                        .timeout(Duration.ofSeconds(timeout))
                        .GET();
                if (ranged) {
                    building.header("Range", "bytes=" + from + "-" + (from + RANGE_BYTES - 1));
                }
                request = building.build();
            } catch (IllegalArgumentException e) {
                return Hop.failed(new Result(Status.BAD_URL, current));
            }

            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();

            if (code >= 300 && code < 400) {
                Optional<String> location = response.headers().firstValue("location");
                response.body().close();
                if (location.isEmpty()) {
                    return Hop.failed(new Result(Status.HTTP_ERROR, String.valueOf(code)));
                }
                current = resolveRedirect(current, location.get());
                if (current == null) {
                    return Hop.failed(new Result(Status.BAD_URL, location.get()));
                }
                continue;
            }

            if (code != 200 && code != 206) {
                response.body().close();
                return Hop.failed(new Result(Status.HTTP_ERROR, String.valueOf(code)));
            }
            return new Hop(response, current, totalOf(response, from), null);
        }
        return Hop.failed(new Result(Status.HTTP_ERROR, "too many redirects"));
    }

    private static long totalOf(HttpResponse<InputStream> response, long from) {
        String range = response.headers().firstValue("content-range").orElse(null);
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0 && slash < range.length() - 1) {
                try {
                    return Long.parseLong(range.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {

                }
            }
        }

        long length = response.headers().firstValueAsLong("content-length").orElse(-1L);
        if (length < 0) return -1L;

        return response.statusCode() == 200 ? length : from + length;
    }

    private InputStream wholeOf(Hop first, int timeout) {
        long end = first.total();
        if (first.response().statusCode() != 206 || end <= RANGE_BYTES) {
            return first.response().body();
        }
        return new RangedStream(first, timeout);
    }

    private final class RangedStream extends InputStream {

        private final int timeout;
        private final String url;
        private final long total;

        private InputStream current;
        private long position;

        RangedStream(Hop first, int timeout) {
            this.timeout = timeout;
            this.url = first.url();
            this.total = first.total();
            this.current = first.response().body();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            while (true) {
                int read = current.read(destination, offset, length);
                if (read > 0) {
                    position += read;
                    return read;
                }
                if (read == 0) return 0;
                if (position >= total || !next()) return -1;
            }
        }

        private boolean next() throws IOException {
            current.close();
            try {
                Hop hop = open(url, position, timeout, true);
                if (hop.failure() != null) {
                    throw new IOException("The download stopped at " + position
                            + " of " + total + " bytes: " + hop.failure().status());
                }
                current = hop.response().body();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted part-way through a download", e);
            }
        }

        @Override
        public void close() throws IOException {
            current.close();
        }
    }

    private Result save(Limits limits, InputStream in, long declared, String contentType,
                        String contentDisposition, String urlHint, String desiredName)
            throws IOException {

        if (declared > limits.maxBytes()) {
            return new Result(Status.TOO_LARGE, human(declared));
        }

        String type = contentType == null ? "" : contentType;
        String name = FileNames.choose(desiredName, contentDisposition, urlHint,
                type, limits.allowed());
        if (name == null) {
            return new Result(Status.BAD_TYPE, type.isEmpty() ? "?" : type);
        }

        Path root = limits.library().root();
        Path target = root.resolve(name).normalize();

        if (!target.startsWith(root)) return new Result(Status.BAD_URL, name);
        if (Files.exists(target)) return new Result(Status.NAME_TAKEN, name);

        Path temp = Files.createTempFile(root, ".cdisc-", ".part");
        try {
            long written = copyCapped(in, temp, limits.maxBytes());
            if (written < 0) {
                Files.deleteIfExists(temp);
                return new Result(Status.TOO_LARGE, human(limits.maxBytes()));
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        Tasks.global(plugin, limits.library()::reload);
        return new Result(Status.OK, name);
    }

    private static long copyCapped(InputStream in, Path target, long maxBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;

        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) return -1;
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static String resolveRedirect(String from, String location) {
        try {
            return new URI(from).resolve(location.trim()).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
