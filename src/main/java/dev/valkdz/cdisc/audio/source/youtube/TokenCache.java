package dev.valkdz.cdisc.audio.source.youtube;

import dev.valkdz.cdisc.util.Json;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

public final class TokenCache {

    public static final String FILE_NAME = "backend-youtube-tokens.json";


    public record Entry(String poToken, String visitorData, String visitorId,
                        long updatedAt, long expiresInSeconds, String source) {

        public long remainingSeconds() {
            long spent = Instant.now().getEpochSecond() - updatedAt;
            return Math.max(0, expiresInSeconds - spent);
        }

        public boolean isUsable() {
            return visitorData != null && !visitorData.isBlank();
        }
    }

    private final File file;

    public TokenCache(File dataFolder) {
        this.file = new File(dataFolder, FILE_NAME);
    }

    public File file() {
        return file;
    }

    public long lastModified() {
        return file.lastModified();
    }

    public Entry read() {
        if (!file.isFile()) return null;

        try {
            Json root = Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            String visitorData = text(root, "visitor-data");
            if (visitorData == null) return null;

            String visitorId = text(root, "visitor-id");
            if (visitorId == null) visitorId = VisitorRenewal.identityOf(visitorData);

            return new Entry(
                    text(root, "po-token"),
                    visitorData,
                    visitorId,
                    root.path("updated-at").asLong(0),
                    root.path("expires-in-seconds").asLong(0),
                    text(root, "source"));
        } catch (IOException | RuntimeException e) {

            return null;
        }
    }

    public void write(String poToken, String visitorData, long expiresInSeconds,
                      String source) throws IOException {

        Json root = Json.object();
        root.put("po-token", poToken == null ? "" : poToken);
        root.put("visitor-data", visitorData == null ? "" : visitorData);
        root.put("visitor-id", VisitorRenewal.identityOf(visitorData));
        root.put("updated-at", Instant.now().getEpochSecond());
        root.put("expires-in-seconds", expiresInSeconds);
        root.put("source", source);

        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

        Files.writeString(file.toPath(),
                root.toPrettyString() + "\n",
                StandardCharsets.UTF_8);
    }

    private static String text(Json root, String field) {
        Json node = root.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }
}
