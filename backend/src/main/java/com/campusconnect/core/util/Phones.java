package com.campusconnect.core.util;

import java.util.regex.Pattern;

/** Indian mobile numbers, stored as "+91" and ten digits starting 6–9 (utils/Phone.js). */
public final class Phones {

    private static final Pattern MOBILE = Pattern.compile("^[6-9]\\d{9}$");

    private Phones() {
    }

    /** Accepts "98765 43210", "+91-98765-43210", "098765 43210"…; returns "+919876543210" or null. */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return MOBILE.matcher(digits).matches() ? "+91" + digits : null;
    }
}
