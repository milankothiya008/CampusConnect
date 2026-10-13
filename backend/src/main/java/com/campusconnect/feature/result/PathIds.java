package com.campusconnect.feature.result;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.web.ObjectIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code mongoIdParam(...)} for routes with several path ids: every invalid one is reported, joined like
 * express-validator does ("Invalid id. Invalid roundId"). Valid ids come back lower-case, so services can compare
 * them with stored ids as strings.
 */
public final class PathIds {

    private PathIds() {
    }

    /** Arguments are name, value pairs; returns the values in the same order. */
    public static String[] require(String... namesAndValues) {
        List<String> problems = new ArrayList<>();
        String[] valid = new String[namesAndValues.length / 2];
        for (int i = 0; i < namesAndValues.length; i += 2) {
            String value = namesAndValues[i + 1];
            if (ObjectIds.isValid(value)) {
                valid[i / 2] = value.toLowerCase(Locale.ROOT);
            } else {
                problems.add("Invalid " + namesAndValues[i]);
            }
        }
        if (!problems.isEmpty()) {
            throw AppException.badRequest(String.join(". ", problems));
        }
        return valid;
    }

    public static String require(String name, String value) {
        return require(new String[] {name, value})[0];
    }
}
