package com.campusconnect.core.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Mongoose {@code populate(path, "a b c")} for the JSON views: loads only the selected fields of many documents
 * in ONE query and returns them as JSON-ready maps ({@code _id} first, ObjectIds as hex strings, dates as
 * {@code Instant}, and every selected field present — {@code null} when the document has none, because the
 * frontend tests {@code !== null}).
 */
@Component
public class Refs {

    private final MongoOperations mongo;

    public Refs(MongoOperations mongo) {
        this.mongo = mongo;
    }

    /**
     * Selected fields of the documents with these ids, keyed by id (missing documents are simply absent). The map
     * accepts {@code get(null)} (an unset optional reference) and returns null.
     */
    public Map<String, Map<String, Object>> load(String collection, Collection<String> ids, String... fields) {
        List<ObjectId> objectIds = ids == null ? List.of() : ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .filter(ObjectId::isValid)
                .map(ObjectId::new)
                .toList();
        if (objectIds.isEmpty()) {
            // Mutable and null-tolerant: callers look up optional references (get(null) must just return null).
            return new HashMap<>();
        }
        Query query = Query.query(Criteria.where("_id").in(objectIds));
        for (String field : fields) {
            guard(collection, field);
            query.fields().include(field);
        }
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Document document : mongo.find(query, Document.class, collection)) {
            Map<String, Object> view = shape(document, fields);
            result.put((String) view.get("_id"), view);
        }
        return result;
    }

    /** One document's selected fields, or null. */
    public Map<String, Object> one(String collection, String id, String... fields) {
        if (id == null) {
            return null;
        }
        return load(collection, List.of(id), fields).get(id);
    }

    public Map<String, Map<String, Object>> users(Collection<String> ids, String... fields) {
        return load("users", ids, fields);
    }

    public Map<String, Object> user(String id, String... fields) {
        return one("users", id, fields);
    }

    public Map<String, Map<String, Object>> clubs(Collection<String> ids, String... fields) {
        return load("clubs", ids, fields);
    }

    public Map<String, Object> club(String id, String... fields) {
        return one("clubs", id, fields);
    }

    public Map<String, Map<String, Object>> events(Collection<String> ids, String... fields) {
        return load("events", ids, fields);
    }

    public Map<String, Object> event(String id, String... fields) {
        return one("events", id, fields);
    }

    /** {@code _id} first, then each requested field (dotted paths stay nested), with BSON values made plain. */
    public static Map<String, Object> shape(Document document, String... fields) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("_id", plain(document.get("_id")));
        for (String field : fields) {
            if (!field.contains(".")) {
                view.put(field, plain(document.get(field)));
                continue;
            }
            String[] path = field.split("\\.");
            Object value = document;
            for (String part : path) {
                value = value instanceof Document nested ? nested.get(part) : null;
            }
            Map<String, Object> target = view;
            for (int i = 0; i < path.length - 1; i++) {
                Object next = target.get(path[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    target.put(path[i], next);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) next;
                target = cast;
            }
            target.put(path[path.length - 1], plain(value));
        }
        return view;
    }

    /** BSON value to a JSON-friendly Java value (recursively). */
    public static Object plain(Object value) {
        if (value instanceof ObjectId id) {
            return id.toHexString();
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Decimal128 decimal) {
            return decimal.bigDecimalValue();
        }
        if (value instanceof Document document) {
            Map<String, Object> map = new LinkedHashMap<>();
            document.forEach((key, nested) -> map.put(key, plain(nested)));
            return map;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(plain(item)));
            return copy;
        }
        return value;
    }

    /** Secrets are never populated, whatever a caller asks for. */
    private static void guard(String collection, String field) {
        String lower = field.toLowerCase(Locale.ROOT);
        if ("users".equals(collection) && (lower.contains("password") || lower.contains("otp") || lower.contains("calendarkey") || lower.contains("token"))) {
            throw new IllegalArgumentException("Refusing to load a secret user field: " + field);
        }
    }
}
