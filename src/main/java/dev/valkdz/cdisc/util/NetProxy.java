package dev.valkdz.cdisc.util;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.util.Locale;
import java.util.logging.Logger;

public final class NetProxy {

    private record Route(String host, int port, String user, String password) {
    }

    private static volatile Route route;

    private NetProxy() {
    }

    public static void configure(String address, String user, String password, Logger log) {
        route = null;
        if (address == null || address.isBlank()) return;

        String rest = address.trim();
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            if (rest.toLowerCase(Locale.ROOT).startsWith("socks")) {
                log.warning("proxy.address is a SOCKS proxy; only HTTP proxies are supported. "
                        + "Requests go out directly.");
                return;
            }
            rest = rest.substring(scheme + 3);
        }
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);

        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String login = rest.substring(0, at);
            rest = rest.substring(at + 1);
            if (user == null || user.isBlank()) {
                int split = login.indexOf(':');
                user = split < 0 ? login : login.substring(0, split);
                password = split < 0 ? "" : login.substring(split + 1);
            }
        }

        int colon = rest.lastIndexOf(':');
        int port = -1;
        try {
            if (colon > 0) port = Integer.parseInt(rest.substring(colon + 1));
        } catch (NumberFormatException ignored) {
        }
        if (port < 1 || port > 65535) {
            log.warning("proxy.address must look like host:port, not '" + address.trim()
                    + "'. Requests go out directly.");
            return;
        }

        boolean login = user != null && !user.isBlank();
        if (login) {
            // The JDK refuses Basic auth on an HTTPS tunnel unless this is cleared before first use.
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
        route = new Route(rest.substring(0, colon), port,
                login ? user : null, login ? (password == null ? "" : password) : null);
        log.info("Every outgoing request goes through the proxy at " + route.host() + ":" + port
                + (login ? ", with a login." : "."));
    }

    public static String address() {
        Route r = route;
        return r == null ? null : r.host() + ":" + r.port();
    }

    public static HttpClient.Builder apply(HttpClient.Builder builder) {
        Route r = route;
        if (r == null) return builder;

        builder.proxy(ProxySelector.of(new InetSocketAddress(r.host(), r.port())));
        if (r.user() != null) {
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return getRequestorType() == RequestorType.PROXY
                            ? new PasswordAuthentication(r.user(), r.password().toCharArray())
                            : null;
                }
            });
        }
        return builder;
    }

    public static void apply(HttpClientBuilder builder) {
        Route r = route;
        if (r == null) return;

        HttpHost proxy = new HttpHost(r.host(), r.port());
        builder.setProxy(proxy);
        if (r.user() != null) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(new AuthScope(proxy),
                    new UsernamePasswordCredentials(r.user(), r.password()));
            builder.setDefaultCredentialsProvider(credentials);
        }
    }
}
