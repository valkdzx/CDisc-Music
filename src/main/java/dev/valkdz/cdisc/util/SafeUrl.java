package dev.valkdz.cdisc.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

public final class SafeUrl {

    public enum Verdict {
        OK,

        BAD_SCHEME,

        MALFORMED,

        UNRESOLVABLE,

        PRIVATE_ADDRESS
    }

    private SafeUrl() {
    }

    public static Verdict judge(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) return Verdict.MALFORMED;

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return Verdict.MALFORMED;
        }

        String scheme = uri.getScheme();
        if (scheme == null) return Verdict.MALFORMED;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return Verdict.BAD_SCHEME;

        String host = uri.getHost();
        if (host == null || host.isEmpty()) return Verdict.MALFORMED;

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException | SecurityException e) {
            return Verdict.UNRESOLVABLE;
        }
        if (addresses.length == 0) return Verdict.UNRESOLVABLE;

        for (InetAddress address : addresses) {
            if (!isPublic(address)) return Verdict.PRIVATE_ADDRESS;
        }
        return Verdict.OK;
    }

    public static boolean isFetchable(String url) {
        return judge(url) == Verdict.OK;
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            int first = octets[0] & 0xFF;
            int second = octets[1] & 0xFF;

            if (first == 100 && second >= 64 && second <= 127) return false;

            if (first == 192 && second == 0 && (octets[2] & 0xFF) == 0) return false;
        } else if (octets.length == 16) {

            if ((octets[0] & 0xFE) == 0xFC) return false;
        }

        return true;
    }
}
