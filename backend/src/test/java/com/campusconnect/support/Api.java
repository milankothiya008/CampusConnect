package com.campusconnect.support;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/** Calls the API as a user (or anonymously), like supertest in the original tests: {@code api.as(user).get(url)}. */
public final class Api {

    private final MockMvc mvc;
    private final JsonMapper json;
    private final String token;
    private final Map<String, String> headers;

    public Api(MockMvc mvc, JsonMapper json) {
        this(mvc, json, null, Map.of());
    }

    private Api(MockMvc mvc, JsonMapper json, String token, Map<String, String> headers) {
        this.mvc = mvc;
        this.json = json;
        this.token = token;
        this.headers = headers;
    }

    public Api as(TestUser user) {
        return new Api(mvc, json, user == null ? null : user.token(), headers);
    }

    public Api withToken(String accessToken) {
        return new Api(mvc, json, accessToken, headers);
    }

    public Api header(String name, String value) {
        Map<String, String> next = new LinkedHashMap<>(headers);
        next.put(name, value);
        return new Api(mvc, json, token, next);
    }

    /** Sends a cookie given as "name=value" (as returned by {@link Response#cookie}). */
    public Api cookie(String cookie) {
        return header("Cookie", cookie);
    }

    public Response get(String url) {
        return send(HttpMethod.GET, url, null, null);
    }

    public Response post(String url) {
        return send(HttpMethod.POST, url, null, null);
    }

    public Response post(String url, Object body) {
        return send(HttpMethod.POST, url, body, null);
    }

    public Response put(String url, Object body) {
        return send(HttpMethod.PUT, url, body, null);
    }

    public Response patch(String url, Object body) {
        return send(HttpMethod.PATCH, url, body, null);
    }

    public Response patch(String url) {
        return send(HttpMethod.PATCH, url, null, null);
    }

    public Response delete(String url) {
        return send(HttpMethod.DELETE, url, null, null);
    }

    public Response delete(String url, Object body) {
        return send(HttpMethod.DELETE, url, body, null);
    }

    /** Any request; {@code customize} can add files, a remote address, other content types... */
    public Response send(HttpMethod method, String url, Object body, Consumer<MockHttpServletRequestBuilder> customize) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, url);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        headers.forEach((name, value) -> {
            if (name.equalsIgnoreCase("Cookie")) {
                for (String pair : value.split(";\s*")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        request.cookie(new jakarta.servlet.http.Cookie(pair.substring(0, eq), pair.substring(eq + 1)));
                    }
                }
            } else {
                request.header(name, value);
            }
        });
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body instanceof String text ? text : json.writeValueAsString(body));
        }
        if (customize != null) {
            customize.accept(request);
        }
        return perform(request);
    }

    public Response perform(org.springframework.test.web.servlet.RequestBuilder request) {
        try {
            MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            Map<String, List<String>> responseHeaders = new HashMap<>();
            for (String name : response.getHeaderNames()) {
                responseHeaders.put(name, response.getHeaders(name));
            }
            return new Response(response.getStatus(), response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8), responseHeaders);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
