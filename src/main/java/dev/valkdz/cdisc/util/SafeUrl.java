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

    private static final byte[] NAT64 = {0, 0x64, (byte) 0xFF, (byte) 0x9B, 0, 0, 0, 0, 0, 0, 0, 0};

    private static final byte[] V4_COMPATIBLE = new byte[12];

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

        return judgeHost(uri.getHost());
    }

    public static Verdict judgeHost(String host) {
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

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        byte[] octets = address.getAddress();
        if (octets.length == 4) return isPublicV4(octets, 0);

        if ((octets[0] & 0xFE) == 0xFC) return false;
        if ((octets[0] & 0xFF) == 0x20 && (octets[1] & 0xFF) == 0x02) return isPublicV4(octets, 2);
        if (startsWith(octets, NAT64) || startsWith(octets, V4_COMPATIBLE)) return isPublicV4(octets, 12);
        return true;
    }

    private static boolean startsWith(byte[] octets, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (octets[i] != prefix[i]) return false;
        }
        return true;
    }

    private static boolean isPublicV4(byte[] octets, int at) {
        int first = octets[at] & 0xFF;
        int second = octets[at + 1] & 0xFF;
        int third = octets[at + 2] & 0xFF;

        if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        if (first == 192 && second == 168) return false;
        if (first == 100 && second >= 64 && second <= 127) return false;
        if (first == 192 && second == 0 && third == 0) return false;
        if (first == 198 && (second == 18 || second == 19)) return false;
        return true;
    }
}
