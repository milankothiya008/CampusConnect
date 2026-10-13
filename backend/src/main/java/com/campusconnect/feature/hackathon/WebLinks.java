package com.campusconnect.feature.hackathon;

import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of the WHATWG URL standard the hackathon links need ({@code new URL(text).toString()} in Node): http(s)
 * links are parsed and serialised the way browsers do — lower-case scheme and host, default port dropped, "/" for an
 * empty path, dot segments resolved, unsafe characters percent-encoded — so stored links are identical to the Node
 * app's ("https://quickbite.example.com" becomes "https://quickbite.example.com/"). Other schemes are only recognised,
 * because the caller refuses them anyway.
 */
final class WebLinks {

    /** A parsed link: its lower-case scheme, and the serialised URL for http(s) links (null for other schemes). */
    record Parsed(String scheme, String href) {
        boolean isHttp() {
            return "http".equals(scheme) || "https".equals(scheme);
        }
    }

    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):(.*)$", Pattern.DOTALL);
    private static final Set<String> HOST_SCHEMES = Set.of("ftp", "ws", "wss");
    private static final String HEX = "0123456789ABCDEF";

    private WebLinks() {
    }

    /** {@code new URL(text)}; null where the URL constructor throws. */
    static Parsed parse(String input) {
        String text = stripControlsAndSpaces(input.replaceAll("[\\t\\n\\r]", ""));
        Matcher matcher = SCHEME.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String scheme = matcher.group(1).toLowerCase(Locale.ROOT);
        String rest = matcher.group(2);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            if (HOST_SCHEMES.contains(scheme)) {
                String authority = rest.replaceFirst("^[/\\\\]*", "").split("[/\\\\?#]", 2)[0];
                String host = authority.substring(authority.lastIndexOf('@') + 1);
                if (host.isEmpty() || host.startsWith(":")) {
                    return null;
                }
            }
            return new Parsed(scheme, null);
        }
        return parseHttp(scheme, rest);
    }

    private static String stripControlsAndSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) <= ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    private static Parsed parseHttp(String scheme, String rest) {
        String afterSlashes = rest.replaceFirst("^[/\\\\]*", "");
        int authorityEnd = indexOfAny(afterSlashes, "/\\?#");
        String authority = authorityEnd < 0 ? afterSlashes : afterSlashes.substring(0, authorityEnd);
        String remainder = authorityEnd < 0 ? "" : afterSlashes.substring(authorityEnd);

        String userinfo = null;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            userinfo = authority.substring(0, at);
            authority = authority.substring(at + 1);
        }
        String hostPart = authority;
        String port = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return null;
            }
            hostPart = authority.substring(0, close + 1);
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) {
                    return null;
                }
                port = after.substring(1);
            }
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                hostPart = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            }
        }
        String host = host(hostPart);
        if (host == null) {
            return null;
        }
        Integer portNumber = null;
        if (port != null && !port.isEmpty()) {
            if (!port.matches("\\d+") || port.replaceFirst("^0+(?=\\d)", "").length() > 5 || Integer.parseInt(port) > 65535) {
                return null;
            }
            portNumber = Integer.parseInt(port);
            if (("http".equals(scheme) && portNumber == 80) || ("https".equals(scheme) && portNumber == 443)) {
                portNumber = null;
            }
        }

        String path = remainder;
        String query = null;
        String fragment = null;
        int hash = path.indexOf('#');
        if (hash >= 0) {
            fragment = path.substring(hash + 1);
            path = path.substring(0, hash);
        }
        int question = path.indexOf('?');
        if (question >= 0) {
            query = path.substring(question + 1);
            path = path.substring(0, question);
        }

        StringBuilder href = new StringBuilder(scheme).append("://");
        if (userinfo != null) {
            int colon = userinfo.indexOf(':');
            String username = encode(colon < 0 ? userinfo : userinfo.substring(0, colon), WebLinks::inUserinfoSet);
            String password = colon < 0 ? "" : encode(userinfo.substring(colon + 1), WebLinks::inUserinfoSet);
            if (!username.isEmpty() || !password.isEmpty()) {
                href.append(username);
                if (!password.isEmpty()) {
                    href.append(':').append(password);
                }
                href.append('@');
            }
        }
        href.append(host);
        if (portNumber != null) {
            href.append(':').append(portNumber);
        }
        href.append(path(path.replace('\\', '/')));
        if (query != null) {
            href.append('?').append(encode(query, WebLinks::inQuerySet));
        }
        if (fragment != null) {
            href.append('#').append(encode(fragment, WebLinks::inFragmentSet));
        }
        return new Parsed(scheme, href.toString());
    }

    private static int indexOfAny(String value, String characters) {
        for (int i = 0; i < value.length(); i++) {
            if (characters.indexOf(value.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- host

    private static String host(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.startsWith("[")) {
            return ipv6(raw.substring(1, raw.length() - 1));
        }
        String decoded = percentDecode(raw);
        String ascii;
        try {
            ascii = decoded.chars().allMatch(c -> c < 0x80) ? decoded : toAscii(decoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
        ascii = ascii.toLowerCase(Locale.ROOT);
        if (ascii.isEmpty() || ascii.chars().anyMatch(c -> c <= 0x20 || c == 0x7F || "#%/:<>?@[\\]^|".indexOf(c) >= 0)) {
            return null;
        }
        return endsInNumber(ascii) ? ipv4(ascii) : ascii;
    }

    private static String toAscii(String domain) {
        List<String> labels = new ArrayList<>();
        for (String label : domain.split("\\.", -1)) {
            labels.add(label.isEmpty() || label.chars().allMatch(c -> c < 0x80) ? label : IDN.toASCII(label, IDN.ALLOW_UNASSIGNED));
        }
        return String.join(".", labels);
    }

    private static boolean endsInNumber(String host) {
        List<String> labels = new ArrayList<>(List.of(host.split("\\.", -1)));
        if (labels.size() > 1 && labels.get(labels.size() - 1).isEmpty()) {
            labels.remove(labels.size() - 1);
        }
        String last = labels.get(labels.size() - 1);
        return !last.isEmpty() && (last.chars().allMatch(Character::isDigit) || last.matches("^0[xX][0-9a-fA-F]*$"));
    }

    /** WHATWG IPv4 parsing: up to four parts, each decimal, octal (leading 0) or hex (0x). */
    private static String ipv4(String host) {
        List<String> parts = new ArrayList<>(List.of(host.split("\\.", -1)));
        if (parts.size() > 1 && parts.get(parts.size() - 1).isEmpty()) {
            parts.remove(parts.size() - 1);
        }
        if (parts.size() > 4) {
            return null;
        }
        long[] numbers = new long[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            int radix = 10;
            if (part.startsWith("0x") || part.startsWith("0X")) {
                part = part.substring(2);
                radix = 16;
            } else if (part.length() > 1 && part.startsWith("0")) {
                part = part.substring(1);
                radix = 8;
            }
            if (part.isEmpty()) {
                numbers[i] = 0;
                continue;
            }
            try {
                numbers[i] = Long.parseLong(part, radix);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        for (int i = 0; i < numbers.length - 1; i++) {
            if (numbers[i] > 255) {
                return null;
            }
        }
        long last = numbers[numbers.length - 1];
        if (last >= Math.pow(256, 5 - numbers.length)) {
            return null;
        }
        long address = last;
        for (int i = 0; i < numbers.length - 1; i++) {
            address += numbers[i] * (long) Math.pow(256, 3 - i);
        }
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "." + ((address >> 8) & 0xFF) + "." + (address & 0xFF);
    }

    /** IPv6 literal, serialised with the longest run of zero pieces compressed. */
    private static String ipv6(String literal) {
        if (literal.isEmpty() || !literal.matches("^[0-9a-fA-F:.]+$")) {
            return null;
        }
        byte[] bytes;
        try {
            InetAddress address = InetAddress.getByName(literal);
            bytes = address instanceof Inet4Address
                    ? new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, address.getAddress()[0], address.getAddress()[1], address.getAddress()[2], address.getAddress()[3]}
                    : address.getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
        int[] pieces = new int[8];
        for (int i = 0; i < 8; i++) {
            pieces[i] = ((bytes[2 * i] & 0xFF) << 8) | (bytes[2 * i + 1] & 0xFF);
        }
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < 8; ) {
            if (pieces[i] != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < 8 && pieces[i] == 0) {
                i++;
            }
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append(i == 0 ? "::" : ":");
                i += bestLength - 1;
                continue;
            }
            out.append(Integer.toHexString(pieces[i]));
            if (i < 7) {
                out.append(':');
            }
        }
        return out.append(']').toString();
    }

    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length && HEX.indexOf(Character.toUpperCase(bytes[i + 1])) >= 0 && HEX.indexOf(Character.toUpperCase(bytes[i + 2])) >= 0) {
                out.write(Integer.parseInt(new String(bytes, i + 1, 2, StandardCharsets.US_ASCII), 16));
                i += 2;
            } else {
                out.write(bytes[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- path, query and fragment

    private static String path(String raw) {
        String[] segments = raw.isEmpty() ? new String[] {""} : raw.substring(raw.startsWith("/") ? 1 : 0).split("/", -1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean last = i == segments.length - 1;
            String lower = segment.toLowerCase(Locale.ROOT);
            if (lower.equals("..") || lower.equals(".%2e") || lower.equals("%2e.") || lower.equals("%2e%2e")) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
                if (last) {
                    out.add("");
                }
            } else if (lower.equals(".") || lower.equals("%2e")) {
                if (last) {
                    out.add("");
                }
            } else {
                out.add(encode(segment, WebLinks::inPathSet));
            }
        }
        return "/" + String.join("/", out);
    }

    private interface EncodeSet {
        boolean contains(int codePoint);
    }

    private static boolean inC0ControlSet(int c) {
        return c < 0x20 || c > 0x7E;
    }

    private static boolean inFragmentSet(int c) {
        return inC0ControlSet(c) || c == ' ' || c == '"' || c == '<' || c == '>' || c == '`';
    }

    /** The special-query percent-encode set (http and https are special schemes). */
    private static boolean inQuerySet(int c) {
        return inC0ControlSet(c) || c == ' ' || c == '"' || c == '#' || c == '<' || c == '>' || c == '\'';
    }

    private static boolean inPathSet(int c) {
        return inC0ControlSet(c) || c == ' ' || c == '"' || c == '#' || c == '<' || c == '>' || c == '?' || c == '^' || c == '`' || c == '{' || c == '}';
    }

    private static boolean inUserinfoSet(int c) {
        return inPathSet(c) || c == '/' || c == ':' || c == ';' || c == '=' || c == '@' || c == '[' || c == '\\' || c == ']' || c == '|';
    }

    private static String encode(String value, EncodeSet set) {
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            if (!set.contains(codePoint)) {
                out.appendCodePoint(codePoint);
                return;
            }
            for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                out.append('%').append(HEX.charAt((b >> 4) & 0xF)).append(HEX.charAt(b & 0xF));
            }
        });
        return out.toString();
    }
}
