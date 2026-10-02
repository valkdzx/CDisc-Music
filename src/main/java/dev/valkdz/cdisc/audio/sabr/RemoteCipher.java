package dev.valkdz.cdisc.audio.sabr;

import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.http.HttpResponse;

// A yt-cipher server, asked when our own reading of the player script fails.
final class RemoteCipher {

    private final String endpoint;
    private final String password;

    RemoteCipher(String base, String password) {
        this.endpoint = (base.endsWith("/") ? base : base + "/") + "resolve_url";
        this.password = password == null ? "" : password;
    }

    String resolve(String streamUrl, String playerUrl, String n) throws IOException {
        Json body = Json.object().put("stream_url", streamUrl).put("player_url", playerUrl);
        if (n != null) body.put("n_param", n);

        HttpResponse<String> response = password.isBlank()
                ? Http.post(endpoint, body.toString(), "Content-Type", "application/json",
                        "User-Agent", "CDisc Music Plugin")
                : Http.post(endpoint, body.toString(), "Content-Type", "application/json",
                        "User-Agent", "CDisc Music Plugin", "Authorization", password);
        if (response.statusCode() != 200) {
            throw new IOException("The remote cipher answered HTTP " + response.statusCode());
        }
        String resolved = Json.parse(response.body()).path("resolved_url").asText(null);
        if (resolved == null || resolved.isBlank()) throw new IOException("The remote cipher resolved nothing");
        return resolved;
    }
}
