package com.campusconnect.feature.club;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A document as Mongoose's {@code toObject()} returned it: every field (nulls included, secrets never), ready for
 * the service to swap reference ids for populated views and add computed fields.
 */
@Component
public class DocumentViews {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final JsonMapper json;

    public DocumentViews(JsonMapper json) {
        this.json = json;
    }

    /** All JSON fields of a full (not projected) document. */
    public Map<String, Object> of(Object document) {
        return json.convertValue(document, MAP);
    }

    /** The {@code Refs} row of an id (null-safe: a missing reference has no row). */
    public static Map<String, Object> row(Map<String, Map<String, Object>> rows, String id) {
        return id == null ? null : rows.get(id);
    }

    /**
     * A populated reference ({@code populate(path, "a b")}): {@code _id} plus exactly these fields of a {@code Refs}
     * row, or null when the referenced document does not exist.
     */
    public static Map<String, Object> pick(Map<String, Object> row, Collection<String> fields) {
        if (row == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("_id", row.get("_id"));
        fields.forEach(field -> view.put(field, row.get(field)));
        return view;
    }
}
