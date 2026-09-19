package dev.valkdz.cdisc.util;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

public final class NetProxy {

    private record Route(String host, int port, String user, String password) {
        Proxy proxy() {
            return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
        }
    }

    private static volatile Route route;

    private static final ProxySelector SELECTOR = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            Route r = route;
            return List.of(r == null ? Proxy.NO_PROXY : r.proxy());
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException e) {
        }
    };

    private static final Authenticator AUTHENTICATOR = new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            Route r = route;
            return r != null && r.user() != null && getRequestorType() == RequestorType.PROXY
                    ? new PasswordAuthentication(r.user(), r.password().toCharArray())
                    : null;
        }
    };

    private static final CredentialsProvider CREDENTIALS = new CredentialsProvider() {
        @Override
        public void setCredentials(AuthScope scope, Credentials credentials) {
        }

        @Override
        public Credentials getCredentials(AuthScope scope) {
            Route r = route;
            if (r == null || r.user() == null) return null;
            boolean ours = (scope.getHost() == null || scope.getHost().equalsIgnoreCase(r.host()))
                    && (scope.getPort() < 0 || scope.getPort() == r.port());
            return ours ? new UsernamePasswordCredentials(r.user(), r.password()) : null;
        }

        @Override
        public void clear() {
        }
    };

    private NetProxy() {
    }

    public static void prepare() {
        // The JDK reads this once, on its first HTTPS request, and refuses Basic auth on a proxy tunnel without it.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
    }

    public static void configure(String address, String user, String password, Logger log) {
        Route before = route;
        route = parse(address, user, password, log);

        Route now = route;
        if (Objects.equals(before, now)) return;
        if (now == null) {
            if (before != null) log.info("Outgoing requests go out directly again, not through a proxy.");
            return;
        }
        log.info("Every outgoing request goes through the proxy at " + now.host() + ":" + now.port()
                + (now.user() != null ? ", with a login." : "."));
    }

    private static Route parse(String address, String user, String password, Logger log) {
        if (address == null || address.isBlank()) return null;

        String rest = address.trim();
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            if (rest.toLowerCase(Locale.ROOT).startsWith("socks")) {
                log.warning("proxy.address is a SOCKS proxy; only HTTP proxies are supported. "
                        + "Requests go out directly.");
                return null;
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
            return null;
        }

        boolean login = user != null && !user.isBlank();
        return new Route(rest.substring(0, colon), port,
                login ? user : null, login ? (password == null ? "" : password) : null);
    }

    public static String address() {
        Route r = route;
        return r == null ? null : r.host() + ":" + r.port();
    }

    // Installed on every client whether or not a proxy is set, so a reload can switch it without new clients.
    public static HttpClient.Builder apply(HttpClient.Builder builder) {
        return builder.proxy(SELECTOR).authenticator(AUTHENTICATOR);
    }

    public static void apply(HttpClientBuilder builder) {
        builder.setRoutePlanner(new SystemDefaultRoutePlanner(SELECTOR));
        builder.setDefaultCredentialsProvider(CREDENTIALS);
    }
}
