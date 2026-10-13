package com.campusconnect.feature.chat.link;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * The SSRF guard of link previews (LinkPreviewService assertPublicUrl / isPublicAddress): the server fetches pages on
 * behalf of users, so it only ever talks to public internet addresses. A link must be http(s) on a standard port
 * without credentials, and every address its host resolves to must be public — not private, loopback, link-local,
 * unique-local, multicast, carrier-grade NAT, benchmarking, documentation or otherwise reserved. IPv6 forms that
 * embed an IPv4 address (mapped, NAT64, 6to4) are judged by that address.
 */
public final class PublicAddresses {

    /** Resolves a host name to all of its addresses (the JDK resolver in production, a stub in tests). */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** A link the server must not fetch. */
    public static final class UnsafeLinkException extends Exception {
        UnsafeLinkException(String reason) {
            super(reason, null, false, false);
        }
    }

    private PublicAddresses() {
    }

    /** True for a public IPv4/IPv6 literal; false for anything private or reserved, and for non-literals. */
    public static boolean isPublicAddress(String ip) {
        if (ip == null) {
            return false;
        }
        try {
            return isPublic(InetAddress.ofLiteral(ip));
        } catch (IllegalArgumentException notALiteral) {
            return false;
        }
    }

    public static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 ? isPublicV4(bytes, 0) : isPublicV6(bytes);
    }

    private static boolean isPublicV4(byte[] bytes, int offset) {
        int a = bytes[offset] & 0xff;
        int b = bytes[offset + 1] & 0xff;
        boolean reserved = a == 0                              // "this network"
                || a == 10                                     // private
                || a == 127                                    // loopback
                || (a == 100 && b >= 64 && b <= 127)           // carrier-grade NAT
                || (a == 169 && b == 254)                      // link-local (cloud metadata endpoints)
                || (a == 172 && b >= 16 && b <= 31)            // private
                || (a == 192 && b == 168)                      // private
                || (a == 192 && b == 0)                        // IETF protocol assignments, TEST-NET-1
                || (a == 198 && (b == 18 || b == 19))          // benchmarking
                || a >= 224;                                   // multicast, reserved, broadcast
        return !reserved;
    }

    private static boolean isPublicV6(byte[] bytes) {
        if (zeros(bytes, 0, 10) && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
            return isPublicV4(bytes, 12);                      // ::ffff:a.b.c.d (IPv4-mapped)
        }
        if (zeros(bytes, 0, 12)) {
            return false;                                      // ::, ::1 and the deprecated ::a.b.c.d
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (first == 0x00 && second == 0x64 && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b && zeros(bytes, 4, 12)) {
            return isPublicV4(bytes, 12);                      // 64:ff9b::a.b.c.d (NAT64)
        }
        if (first == 0x20 && second == 0x02) {
            return isPublicV4(bytes, 2);                       // 2002:aabb:ccdd:: (6to4)
        }
        if (first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) {
            return false;                                      // 2001:db8::/32 documentation
        }
        return (first & 0xfe) != 0xfc                          // fc00::/7 unique local
                && !(first == 0xfe && (second & 0xc0) == 0x80) // fe80::/10 link-local
                && !(first == 0xfe && (second & 0xc0) == 0xc0) // fec0::/10 site-local (deprecated)
                && first != 0xff;                              // ff00::/8 multicast
    }

    private static boolean zeros(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Refuses the link unless it is http(s) on a standard port, has no credentials and every address its host
     * resolves to is public. The JDK caches the lookup (30 s by default), so the HTTP client then connects to the
     * very addresses checked here rather than asking DNS again.
     */
    public static URI assertPublicUrl(String raw, Resolver resolver) throws UnsafeLinkException {
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException | NullPointerException e) {
            throw new UnsafeLinkException("Invalid link");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new UnsafeLinkException("Only web links");
        }
        if (uri.getRawUserInfo() != null) {
            throw new UnsafeLinkException("No credentials in links");
        }
        if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) {
            throw new UnsafeLinkException("Only standard ports");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new UnsafeLinkException("Invalid link");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        InetAddress[] addresses;
        try {
            addresses = new InetAddress[] {InetAddress.ofLiteral(host)};
        } catch (IllegalArgumentException notALiteral) {
            try {
                addresses = resolver.resolve(host);
            } catch (UnknownHostException e) {
                throw new UnsafeLinkException("Unknown host");
            }
        }
        if (addresses == null || addresses.length == 0) {
            throw new UnsafeLinkException("Address not allowed");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new UnsafeLinkException("Address not allowed");
            }
        }
        return uri;
    }
}
