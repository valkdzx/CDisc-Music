package dev.valkdz.cdisc.audio.source.youtube;

import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YoutubeAccount {

    private static final String TV_PAGE = "https://www.youtube.com/tv";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String DEVICE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String SCOPES = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube";
    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String COBALT_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold "
            + "(unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)";
    // The stock Cobalt TV is refused with "The page needs to be reloaded"; a Samsung TV is not,
    // and only the 5.x line still gets direct (signature-scrambled) links instead of SABR.
    private static final String TIZEN_UA = "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 "
            + "(KHTML, like Gecko) Version/2.4.0 TV Safari/538.1";

    private static final Pattern BUNDLE = Pattern.compile("src\\s*=\\s*['\"](/s/_/kabuki/_/js/[^'\"]+m=base)['\"]");
    private static final Pattern CLIENT = Pattern.compile("clientId:\"([^\"]+)\",[^\"]*?:\"([^\"]+)\"");
    private static final Pattern VERSION = Pattern.compile("\"INNERTUBE_CLIENT_VERSION\":\"\\d+\\.(\\d{8})");

    private static final long PAGE_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final long RENEW_AHEAD_MS = 10 * 60 * 1000L;
    private static final String FALLBACK_DATE = "20261014";

    public record DeviceCode(String userCode, String verificationUrl, int expiresInSeconds) {}

    private volatile String refreshToken;
    private volatile boolean signedOut;

    private String clientId;
    private String clientSecret;
    private String bundle;
    private String versionDate;
    private long pageUntil;

    private String accessToken;
    private long accessUntil;
    private final AtomicBoolean renewing = new AtomicBoolean();

    public synchronized void useToken(String token) {
        String wanted = blankToNull(token);
        if (java.util.Objects.equals(wanted, refreshToken)) return;
        refreshToken = wanted;
        signedOut = false;
        accessToken = null;
        accessUntil = 0;
    }

    public boolean usable() {
        return refreshToken != null && !signedOut;
    }

    public boolean linked() {
        return refreshToken != null;
    }

    public boolean signedOut() {
        return signedOut;
    }

    public void warmUp() {
        if (!usable()) return;
        CompletableFuture.runAsync(() -> {
            try {
                token();
            } catch (IOException ignored) {
            }
        });
    }

    public void renewIfDue() {
        if (!usable()) return;
        long now = System.currentTimeMillis();
        boolean due;
        synchronized (this) {
            due = accessToken == null || now > accessUntil - RENEW_AHEAD_MS || now > pageUntil;
        }
        if (due) renewLater();
    }

    InnerTubePlayer.ClientIdentity identity(int signatureTimestamp) throws IOException {
        String token = token();
        String date;
        synchronized (this) {
            date = versionDate == null ? FALLBACK_DATE : versionDate;
        }
        return new InnerTubePlayer.ClientIdentity("TVHTML5", 7, "5." + date, "Tizen", "2.4.0", TIZEN_UA,
                "Samsung", "UKS9800", null, null, token, signatureTimestamp);
    }

    private String token() throws IOException {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (accessToken != null && now < accessUntil) {
                if (now > accessUntil - RENEW_AHEAD_MS || now > pageUntil) renewLater();
                return accessToken;
            }
        }
        return refresh();
    }

    private void renewLater() {
        if (!renewing.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            try {
                if (System.currentTimeMillis() > pageUntilNow()) readPage();
                refresh();
            } catch (IOException ignored) {
            } finally {
                renewing.set(false);
            }
        });
    }

    private synchronized long pageUntilNow() {
        return pageUntil;
    }

    private String refresh() throws IOException {
        String token = refreshToken;
        if (token == null || signedOut) throw new IOException("no YouTube account is linked");

        Json answer = post(TOKEN_URL, form(true, "refresh_token", token, "grant_type", "refresh_token"));
        String error = answer.path("error").asText(null);
        if ("invalid_client".equals(error)) {
            forgetClient();
            answer = post(TOKEN_URL, form(true, "refresh_token", token, "grant_type", "refresh_token"));
            error = answer.path("error").asText(null);
        }
        if ("invalid_grant".equals(error)) {
            signedOut = true;
            throw new IOException("the linked YouTube account was signed out; run /cdisc admin oauth-setup again");
        }
        String access = answer.path("access_token").asText(null);
        if (access == null) throw new IOException("Google refused to renew the YouTube token: " + error);

        synchronized (this) {
            accessToken = access;
            accessUntil = System.currentTimeMillis() + answer.path("expires_in").asLong(3600) * 1000L;
        }
        return access;
    }

    // Blocks until the code is entered or expires (up to half an hour): never on a server thread.
    public String link(Consumer<DeviceCode> show) throws IOException {
        Json code = post(DEVICE_URL, form(false, "scope", SCOPES,
                "device_id", UUID.randomUUID().toString().replace("-", ""), "device_model", "ytlr::"));
        String deviceCode = code.path("device_code").asText(null);
        if (deviceCode == null) {
            throw new IOException("Google gave no device code: " + code.path("error").asText("no answer"));
        }

        int expires = code.path("expires_in").asInt(1800);
        show.accept(new DeviceCode(code.path("user_code").asText(""),
                code.path("verification_url").asText("https://www.google.com/device"), expires));

        long interval = Math.max(1, code.path("interval").asInt(5)) * 1000L;
        long deadline = System.currentTimeMillis() + expires * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("the sign-in was interrupted", e);
            }

            Json answer = post(TOKEN_URL, form(true, "code", deviceCode,
                    "grant_type", "http://oauth.net/grant_type/device/1.0"));
            String error = answer.path("error").asText(null);
            if ("authorization_pending".equals(error)) continue;
            if ("slow_down".equals(error)) {
                interval += 2000;
                continue;
            }
            if (error != null) throw new IOException(answer.path("error_description").asText(error));

            String kept = answer.path("refresh_token").asText(null);
            if (kept == null) throw new IOException("Google signed in but gave no refresh token");
            synchronized (this) {
                refreshToken = kept;
                signedOut = false;
                accessToken = answer.path("access_token").asText(null);
                accessUntil = System.currentTimeMillis() + answer.path("expires_in").asLong(3600) * 1000L;
            }
            return kept;
        }
        throw new IOException("the code expired before it was entered");
    }

    private String form(boolean withSecret, String... pairs) throws IOException {
        String[] client = client();
        StringBuilder body = new StringBuilder("client_id=").append(encode(client[0]));
        if (withSecret) body.append("&client_secret=").append(encode(client[1]));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            body.append('&').append(pairs[i]).append('=').append(encode(pairs[i + 1]));
        }
        return body.toString();
    }

    private String[] client() throws IOException {
        synchronized (this) {
            if (clientId != null) return new String[]{clientId, clientSecret};
        }
        if (bundleUrl() == null) readPage();

        String script = Http.text("https://www.youtube.com" + bundleUrl(), "User-Agent", COBALT_UA);
        Matcher found = CLIENT.matcher(script);
        if (!found.find()) throw new IOException("the YouTube TV app no longer names its sign-in client");

        synchronized (this) {
            clientId = found.group(1);
            clientSecret = found.group(2);
            return new String[]{clientId, clientSecret};
        }
    }

    private synchronized String bundleUrl() {
        return bundle;
    }

    private synchronized void forgetClient() {
        clientId = null;
        clientSecret = null;
        bundle = null;
    }

    private void readPage() throws IOException {
        String page = Http.text(TV_PAGE, "User-Agent", COBALT_UA, "Accept-Language", "en-US,en;q=0.9");
        Matcher script = BUNDLE.matcher(page);
        Matcher version = VERSION.matcher(page);

        synchronized (this) {
            if (script.find()) {
                if (!script.group(1).equals(bundle)) {
                    clientId = null;
                    clientSecret = null;
                }
                bundle = script.group(1);
            }
            if (version.find()) versionDate = version.group(1);
            pageUntil = System.currentTimeMillis() + PAGE_TTL_MS;
        }
        if (bundleUrl() == null) throw new IOException("the YouTube TV page carried no app script");
    }

    private static Json post(String url, String body) throws IOException {
        HttpResponse<String> response = Http.post(url, body, "Content-Type", FORM);
        try {
            return Json.parse(response.body());
        } catch (IOException e) {
            throw new IOException(url + " answered " + response.statusCode(), e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
