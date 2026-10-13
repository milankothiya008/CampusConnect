package com.campusconnect.core.web;

/**
 * Page/limit parsing identical to utils/Query.js: page &ge; 1, 1 &le; limit &le; max, unparsable values fall back to
 * the defaults.
 */
public record Pagination(int page, int limit) {

    public static Pagination of(String page, String limit, int defaultLimit, int maxLimit) {
        int p = Math.max(1, parse(page, 1));
        int l = Math.min(maxLimit, Math.max(1, parse(limit, defaultLimit)));
        return new Pagination(p, l);
    }

    public static Pagination of(String page, String limit, int defaultLimit) {
        return of(page, limit, defaultLimit, 50);
    }

    public int skip() {
        return (page - 1) * limit;
    }

    public PageMeta meta(long total) {
        return new PageMeta(total, page, limit, Math.max(1, (total + limit - 1) / limit));
    }

    private static int parse(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        // Like parseInt: leading digits count ("12abc" -> 12), anything else falls back.
        String trimmed = value.trim();
        int end = 0;
        if (end < trimmed.length() && (trimmed.charAt(0) == '-' || trimmed.charAt(0) == '+')) {
            end++;
        }
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        try {
            int parsed = Integer.parseInt(trimmed.substring(0, end));
            return parsed == 0 ? fallback : parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
