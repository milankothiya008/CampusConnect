package com.campusconnect.feature.club;

import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The WHATWG URL parser and serializer (what Node's {@code new URL(...)} implements) for http and https addresses:
 * enough to accept and clean club links exactly like utils/ClubLinks.js — lower-case and IDNA hosts, IPv4 numbers,
 * default ports dropped, "." and ".." segments resolved, unsafe characters percent-encoded and an empty path
 * written as "/". {@link #parse} returns null where {@code new URL} throws.
 */
record WebUrl(String scheme, boolean credentials, String host, String port, String path, String query, String fragment) {

    private static final Pattern EDGE_CONTROLS = Pattern.compile("^[\\u0000-\\u0020]+|[\\u0000-\\u0020]+$");
    private static final Pattern TAB_OR_NEWLINE = Pattern.compile("[\\t\\n\\r]");
    private static final String FORBIDDEN_DOMAIN = " #%/:<>?@[\\]^|\u007F";
    private static final String QUERY_SET = " \"#<>'";
    private static final String PATH_SET = " \"#<>?^`{}";
    private static final String FRAGMENT_SET = " \"<>`";

    boolean isHttp() {
        return "http".equals(scheme) || "https".equals(scheme);
    }

    @Override
    public String toString() {
        return scheme + "://" + host + (port == null ? "" : ":" + port) + path + (query == null ? "" : "?" + query) + (fragment == null ? "" : "#" + fragment);
    }

    /** The parsed address, or null when it is not a valid URL. Other schemes are returned with only the scheme set. */
    static WebUrl parse(String input) {
        String text = TAB_OR_NEWLINE.matcher(EDGE_CONTROLS.matcher(input).replaceAll("")).replaceAll("");
        int colon = text.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String scheme = text.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return new WebUrl(scheme, false, "", null, "", null, null);
        }
        String rest = text.substring(colon + 1);
        int start = 0;
        while (start < rest.length() && (rest.charAt(start) == '/' || rest.charAt(start) == '\\')) {
            start++;
        }
        int end = start;
        while (end < rest.length() && "/\\?#".indexOf(rest.charAt(end)) < 0) {
            end++;
        }
        String authority = rest.substring(start, end);
        String remainder = rest.substring(end);

        boolean credentials = false;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            String userinfo = authority.substring(0, at);
            int separator = userinfo.indexOf(':');
            String username = separator < 0 ? userinfo : userinfo.substring(0, separator);
            String password = separator < 0 ? "" : userinfo.substring(separator + 1);
            credentials = !username.isEmpty() || !password.isEmpty();
            authority = authority.substring(at + 1);
            if (authority.isEmpty()) {
                return null;
            }
        }

        String hostText = authority;
        String port = null;
        int portStart = authority.startsWith("[") ? -1 : authority.indexOf(':');
        if (portStart >= 0) {
            hostText = authority.substring(0, portStart);
            String digits = authority.substring(portStart + 1);
            if (!digits.isEmpty()) {
                if (!digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    return null;
                }
                String value = digits.replaceFirst("^0+(?=\\d)", "");
                if (value.length() > 5 || Integer.parseInt(value) > 65535) {
                    return null;
                }
                port = value.equals("https".equals(scheme) ? "443" : "80") ? null : value;
            }
        }
        String host = parseHost(hostText);
        if (host == null) {
            return null;
        }

        String fragment = null;
        int hash = remainder.indexOf('#');
        if (hash >= 0) {
            fragment = encode(remainder.substring(hash + 1), FRAGMENT_SET);
            remainder = remainder.substring(0, hash);
        }
        String query = null;
        int question = remainder.indexOf('?');
        if (question >= 0) {
            query = encode(remainder.substring(question + 1), QUERY_SET);
            remainder = remainder.substring(0, question);
        }
        return new WebUrl(scheme, credentials, host, port, parsePath(remainder.replace('\\', '/')), query, fragment);
    }

    private static String parseHost(String text) {
        if (text.isEmpty() || text.startsWith("[")) {
            return null;
        }
        String decoded = percentDecode(text);
        String ascii;
        if (decoded.chars().allMatch(c -> c < 0x80)) {
            ascii = decoded.toLowerCase(Locale.ROOT);
        } else {
            try {
                ascii = IDN.toASCII(decoded, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (ascii.isEmpty() || ascii.chars().anyMatch(c -> c < 0x20 || FORBIDDEN_DOMAIN.indexOf(c) >= 0)) {
            return null;
        }
        return endsInNumber(ascii) ? parseIpv4(ascii) : ascii;
    }

    private static boolean endsInNumber(String host) {
        List<String> parts = new ArrayList<>(List.of(host.split("\\.", -1)));
        if (parts.getLast().isEmpty()) {
            if (parts.size() == 1) {
                return false;
            }
            parts.removeLast();
        }
        String last = parts.getLast();
        return !last.isEmpty() && (last.chars().allMatch(c -> c >= '0' && c <= '9') || last.matches("(?i)0x[0-9a-f]*"));
    }

    private static String parseIpv4(String host) {
        List<String> parts = new ArrayList<>(List.of(host.split("\\.", -1)));
        if (parts.getLast().isEmpty() && parts.size() > 1) {
            parts.removeLast();
        }
        if (parts.size() > 4) {
            return null;
        }
        List<Long> numbers = new ArrayList<>();
        for (String part : parts) {
            Long number = ipv4Number(part);
            if (number == null) {
                return null;
            }
            numbers.add(number);
        }
        for (int i = 0; i < numbers.size() - 1; i++) {
            if (numbers.get(i) > 255) {
                return null;
            }
        }
        long last = numbers.getLast();
        if (last >= Math.pow(256, 5 - numbers.size())) {
            return null;
        }
        long address = last;
        for (int i = 0; i < numbers.size() - 1; i++) {
            address += numbers.get(i) * (long) Math.pow(256, 3 - i);
        }
        return (address >> 24 & 255) + "." + (address >> 16 & 255) + "." + (address >> 8 & 255) + "." + (address & 255);
    }

    private static Long ipv4Number(String part) {
        if (part.isEmpty()) {
            return null;
        }
        int radix = 10;
        String digits = part;
        if (part.length() >= 2 && (part.startsWith("0x") || part.startsWith("0X"))) {
            radix = 16;
            digits = part.substring(2);
        } else if (part.length() >= 2 && part.startsWith("0")) {
            radix = 8;
            digits = part.substring(1);
        }
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            long value = Long.parseLong(digits, radix);
            return value > 0xFFFFFFFFL ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Path segments with "." and ".." resolved; always starts with "/". */
    private static String parsePath(String path) {
        if (path.isEmpty()) {
            return "/";
        }
        String[] tokens = path.substring(1).split("/", -1);
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            boolean last = i == tokens.length - 1;
            String token = tokens[i];
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.equals("..") || lower.equals(".%2e") || lower.equals("%2e.") || lower.equals("%2e%2e")) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                if (last) {
                    segments.add("");
                }
            } else if (lower.equals(".") || lower.equals("%2e")) {
                if (last) {
                    segments.add("");
                }
            } else {
                segments.add(encode(token, PATH_SET));
            }
        }
        return "/" + String.join("/", segments);
    }

    /** Percent-encodes C0 controls, non-ASCII characters (as UTF-8) and the given set; existing escapes stay. */
    private static String encode(String text, String set) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            if (codePoint < 0x20 || codePoint > 0x7E || set.indexOf(codePoint) >= 0) {
                for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(String.format("%02X", b & 0xFF));
                }
            } else {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    private static String percentDecode(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '%' && i + 2 < raw.length && isHex(raw[i + 1]) && isHex(raw[i + 2])) {
                bytes.write(Integer.parseInt(new String(raw, i + 1, 2, StandardCharsets.US_ASCII), 16));
                i += 2;
            } else {
                bytes.write(raw[i]);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(byte b) {
        return b >= '0' && b <= '9' || b >= 'a' && b <= 'f' || b >= 'A' && b <= 'F';
    }
}
