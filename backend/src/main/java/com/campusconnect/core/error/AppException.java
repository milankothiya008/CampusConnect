package com.campusconnect.core.error;

import lombok.Getter;

/**
 * An expected ("operational") error with an HTTP status, a message shown to the user and an optional error
 * code and details — the Java counterpart of utils/AppError.js. Rendered by {@link GlobalExceptionHandler}.
 */
@Getter
public class AppException extends RuntimeException {

    private final int status;
    private final ErrorCode code;
    private final transient Object details;

    public AppException(int status, String message, ErrorCode code, Object details) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public AppException(int status, String message, ErrorCode code) {
        this(status, message, code, null);
    }

    public static AppException of(int status, String message, ErrorCode code) {
        return new AppException(status, message, code);
    }

    public static AppException badRequest(String message) {
        return new AppException(400, message, ErrorCode.VALIDATION_ERROR);
    }

    public static AppException badRequest(String message, ErrorCode code) {
        return new AppException(400, message, code);
    }

    public static AppException unauthorized(String message) {
        return new AppException(401, message, ErrorCode.UNAUTHORIZED);
    }

    public static AppException forbidden(String message) {
        return new AppException(403, message, ErrorCode.FORBIDDEN);
    }

    public static AppException forbidden(String message, ErrorCode code) {
        return new AppException(403, message, code);
    }

    public static AppException notFound(String message) {
        return new AppException(404, message, ErrorCode.NOT_FOUND);
    }

    public static AppException conflict(String message) {
        return new AppException(409, message, ErrorCode.CONFLICT);
    }

    public static AppException conflict(String message, ErrorCode code) {
        return new AppException(409, message, code);
    }

    /** A copy carrying {@code details} (e.g. the clashing events of a SCHEDULE_CONFLICT). */
    public AppException withDetails(Object value) {
        return new AppException(status, getMessage(), code, value);
    }

    /** "fail" for 4xx, "error" otherwise — the {@code status} field of the error body. */
    public String statusText() {
        return status >= 400 && status < 500 ? "fail" : "error";
    }
}
