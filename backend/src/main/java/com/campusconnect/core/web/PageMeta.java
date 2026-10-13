package com.campusconnect.core.web;

/** {@code { total, page, limit, totalPages }} (utils/Query.js paginationMeta). */
public record PageMeta(long total, int page, int limit, long totalPages) {
}
