package com.campusconnect.feature.feed;

import com.campusconnect.core.web.validation.TrimmedSize;
import com.campusconnect.domain.social.FeedAudience;
import com.campusconnect.domain.social.FeedPostType;
import com.campusconnect.domain.social.FeedVisibility;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Request bodies (validators/RequestValidators.js feedPostRules, audienceRules) and views of the feed routes. */
public final class FeedDtos {

    static final String OBJECT_ID = "^[0-9a-fA-F]{24}$";

    private FeedDtos() {
    }

    /** Who an announcement goes to (audienceRules); handed to AudienceService as the raw payload it parses. */
    public record AudienceBody(
            @Pattern(regexp = "EVERYONE|FOLLOWERS|MEMBERS|CUSTOM", message = "Choose who should get this") String mode,
            @Size(max = 30, message = "Invalid value") List<String> roles,
            @Size(max = 30, message = "Invalid value") List<String> departments,
            @Size(max = 30, message = "Invalid value") List<String> batches,
            @Size(max = 200, message = "Choose at most 200 people") List<@Pattern(regexp = OBJECT_ID, message = "Invalid value") String> users,
            Boolean includeMentor) {

        Map<String, Object> asMap() {
            Map<String, Object> raw = new HashMap<>();
            raw.put("mode", mode);
            raw.put("roles", roles);
            raw.put("departments", departments);
            raw.put("batches", batches);
            raw.put("users", users);
            raw.put("includeMentor", includeMentor);
            return raw;
        }
    }

    /** The parts of a request AudienceService.parse reads ({@code visibility} is the old way to say MEMBERS). */
    static Map<String, Object> audiencePayload(AudienceBody audience, String visibility) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("visibility", visibility);
        if (audience != null) {
            payload.put("audience", audience.asMap());
        }
        return payload;
    }

    /** feedPostRules. */
    public record CreatePostBody(
            @NotNull(message = "Club is required") @Pattern(regexp = OBJECT_ID, message = "Club is required") String club,
            @Pattern(regexp = "ANNOUNCEMENT|CLUB_UPDATE|EVENT_UPDATE", message = "Invalid post type") String type,
            @NotNull(message = "Title must be 3-200 characters") @TrimmedSize(min = 3, max = 200, message = "Title must be 3-200 characters") String title,
            @Size(max = 4000, message = "Invalid value") String body,
            // optional({ values: "falsy" }): an empty string means "no event".
            @Pattern(regexp = "^$|" + OBJECT_ID, message = "Invalid value") String event,
            @Pattern(regexp = "PUBLIC|MEMBERS", message = "Invalid value") String visibility,
            @Valid AudienceBody audience,
            Boolean sendEmail,
            @Pattern(regexp = "(?is)^$|^(https?://|/uploads/).*$", message = "image must be an http(s) URL or an uploaded file") String image) {
    }

    /** POST /feed/audience-preview: the club and the audience being composed. */
    public record AudiencePreviewBody(
            @NotNull(message = "Club is required") @Pattern(regexp = OBJECT_ID, message = "Club is required") String club,
            String visibility,
            @Valid AudienceBody audience) {
    }

    /** GET /feed query string ({@code club} and {@code event} are optional ObjectIds). */
    public record FeedQuery(
            @Pattern(regexp = OBJECT_ID, message = "Invalid value") String club,
            @Pattern(regexp = OBJECT_ID, message = "Invalid value") String event,
            String types,
            String type,
            String scope,
            String page,
            String limit) {
    }

    /**
     * A post as the feed shows it (FeedService populatePost): the club, author, event (with its venue) and result
     * (with the winners' names) populated; the recipient list is never included.
     */
    public record FeedPostView(
            @JsonProperty("_id") String id,
            FeedPostType type,
            Map<String, Object> club,
            Map<String, Object> event,
            Map<String, Object> result,
            Map<String, Object> author,
            String title,
            String body,
            String image,
            FeedVisibility visibility,
            @JsonProperty("isSystem") boolean isSystem,
            FeedAudience audience,
            Integer recipientCount,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** How many people an announcement would reach, and how it will be described. */
    public record AudiencePreview(Object count, Object email, String label) {
    }
}
