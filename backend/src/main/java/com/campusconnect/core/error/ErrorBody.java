package com.campusconnect.core.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The JSON error body: {@code { success:false, status, message, errorCode, details? }} (middleware/ErrorHandler.js). */
public record ErrorBody(
        boolean success,
        String status,
        String message,
        ErrorCode errorCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) Object details) {

    public static ErrorBody of(int httpStatus, String message, ErrorCode code, Object details) {
        return new ErrorBody(false, httpStatus >= 400 && httpStatus < 500 ? "fail" : "error", message, code, details);
    }

    public static ErrorBody of(int httpStatus, String message, ErrorCode code) {
        return of(httpStatus, message, code, null);
    }
}
