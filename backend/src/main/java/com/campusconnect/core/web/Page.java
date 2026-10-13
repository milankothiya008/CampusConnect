package com.campusconnect.core.web;

import java.util.List;

/** A page of results plus its {@link PageMeta} (what list services return to controllers). */
public record Page<T>(List<T> items, PageMeta meta) {

    public static <T> Page<T> of(List<T> items, Pagination pagination, long total) {
        return new Page<>(items, pagination.meta(total));
    }
}
