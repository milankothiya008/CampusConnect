package com.campusconnect.support;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An HTTP response of the API under test, with JSONPath access to the body ({@code $.data.user.email}). */
public record Response(int status, String body, Map<String, List<String>> headers) {

    public <T> T at(String path) {
        return JsonPath.read(body, path);
    }

    public boolean has(String path) {
        try {
            JsonPath.read(body, path);
            return true;
        } catch (PathNotFoundException e) {
            return false;
        }
    }

    public String string(String path) {
        Object value = at(path);
        return value == null ? null : String.valueOf(value);
    }

    public String message() {
        return string("$.message");
    }

    public String errorCode() {
        return has("$.errorCode") ? string("$.errorCode") : null;
    }

    public List<String> header(String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).findFirst().map(Map.Entry::getValue).orElse(List.of());
    }

    /** "name=value" of a Set-Cookie header, ready to send back as a Cookie header. */
    public Optional<String> cookie(String name) {
        return header("Set-Cookie").stream().filter(cookie -> cookie.startsWith(name + "=")).map(cookie -> cookie.split(";", 2)[0]).findFirst();
    }

    @Override
    public String toString() {
        return status + " " + body;
    }
}
