package com.campusconnect.core.mail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Brevo's HTTPS e-mail API (port 443) — works where hosts block outgoing SMTP ports. */
public class BrevoMailTransport implements MailTransport {

    private static final Pattern ADDRESS = Pattern.compile("^\\s*\"?([^\"<]*?)\"?\\s*<([^>]+)>\\s*$");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;
    private final String apiKey;
    private final Map<String, String> sender;

    public BrevoMailTransport(RestClient client, String apiKey, String from) {
        this.client = client;
        this.apiKey = apiKey;
        this.sender = parseAddress(from);
    }

    @Override
    public String mode() {
        return "brevo";
    }

    @Override
    public void send(MailMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", sender);
        body.put("to", List.of(Map.of("email", message.to())));
        body.put("subject", message.subject());
        body.put("htmlContent", message.html());
        body.put("textContent", message.text());
        if (message.headers() != null && !message.headers().isEmpty()) {
            body.put("headers", message.headers());
        }
        client.post()
                .uri("https://api.brevo.com/v3/smtp/email")
                .header("api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (request, response) -> {
                    String code = "";
                    String detail = "";
                    try {
                        Map<?, ?> error = JSON.readValue(response.getBody(), Map.class);
                        code = error.get("code") == null ? "" : String.valueOf(error.get("code"));
                        detail = error.get("message") == null ? "" : String.valueOf(error.get("message"));
                    } catch (RuntimeException ignored) {
                        // Not JSON: the status alone explains it.
                    }
                    throw new IllegalStateException(("Brevo refused the email (" + response.getStatusCode().value() + " " + code + " " + detail).trim() + ")");
                })
                .toBodilessEntity();
    }

    /** "CampusConnect <team@example.com>" -> { name, email }. */
    static Map<String, String> parseAddress(String value) {
        Matcher match = ADDRESS.matcher(String.valueOf(value == null ? "" : value));
        Map<String, String> address = new LinkedHashMap<>();
        if (match.matches()) {
            String name = match.group(1).trim();
            if (!name.isEmpty()) {
                address.put("name", name);
            }
            address.put("email", match.group(2).trim());
        } else {
            address.put("email", String.valueOf(value == null ? "" : value).trim());
        }
        return address;
    }
}
