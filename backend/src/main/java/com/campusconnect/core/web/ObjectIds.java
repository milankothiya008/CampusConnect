package com.campusconnect.core.web;

import com.campusconnect.core.error.AppException;
import java.util.Collection;
import java.util.List;
import org.bson.types.ObjectId;

/** ObjectId validation for path parameters and bodies (validators/RequestValidators.js mongoIdParam/mongoIdBody). */
public final class ObjectIds {

    private ObjectIds() {
    }

    public static boolean isValid(String value) {
        return value != null && ObjectId.isValid(value);
    }

    /** A path parameter: 400 "Invalid {name}" when it is not an ObjectId. */
    public static String require(String value, String name) {
        if (!isValid(value)) {
            throw AppException.badRequest("Invalid " + name);
        }
        return value;
    }

    /** A body field: 400 "A valid {name} is required". */
    public static String requireBody(String value, String name) {
        if (!isValid(value)) {
            throw AppException.badRequest("A valid " + name + " is required");
        }
        return value;
    }

    /** The valid ids of a list, de-duplicated, order kept. */
    public static List<String> valid(Collection<String> values) {
        return values == null ? List.of() : values.stream().filter(ObjectIds::isValid).distinct().toList();
    }
}
