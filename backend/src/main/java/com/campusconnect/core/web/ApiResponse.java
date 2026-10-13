package com.campusconnect.core.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Success envelope {@code { success: true, message, data?, meta? }} (utils/ApiResponse.js): {@code data} and
 * {@code meta} are left out when null, exactly like the Express helper.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, String message, T data, Object meta) {

    public static <T> ApiResponse<T> ok(String message) {
        return new ApiResponse<>(true, message, null, null);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, null);
    }

    public static <T> ApiResponse<T> ok(String message, T data, Object meta) {
        return new ApiResponse<>(true, message, data, meta);
    }

    /** A page of items with its pagination meta. */
    public static <T> ApiResponse<java.util.List<T>> page(String message, Page<T> page) {
        return new ApiResponse<>(true, message, page.items(), page.meta());
    }
}
