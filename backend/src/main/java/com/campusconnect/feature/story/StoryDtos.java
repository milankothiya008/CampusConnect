package com.campusconnect.feature.story;

import com.campusconnect.domain.common.MediaKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request bodies (routes/StoryRoutes.js rules) and views of the story routes. */
public final class StoryDtos {

    static final String OBJECT_ID = "^[0-9a-fA-F]{24}$";

    private StoryDtos() {
    }

    public record UploadTicketBody(
            @NotNull(message = "Choose a club") @Pattern(regexp = OBJECT_ID, message = "Choose a club") String club,
            @NotNull(message = "Choose a photo or a video") @Pattern(regexp = "IMAGE|VIDEO", message = "Choose a photo or a video") String kind) {
    }

    /**
     * {@code media} is what the browser got back from the upload (Cloudinary's signed response or the local upload
     * token), or {@code {provider: "event"}} to share the linked event's poster.
     */
    public record CreateStoryBody(
            @NotNull(message = "Choose a club") @Pattern(regexp = OBJECT_ID, message = "Choose a club") String club,
            @NotNull(message = "Add a photo or video") Map<String, Object> media,
            @Size(max = 200, message = "Captions can be up to 200 characters") String caption,
            @Pattern(regexp = "^$|" + OBJECT_ID, message = "Invalid event") String event) {
    }

    public record LikeBody(@NotNull(message = "liked must be true or false") Boolean liked) {
    }

    public record PageQuery(String page, String limit) {
    }

    /** A file saved to development storage, ready to attach to a story with its token. */
    public record LocalMedia(String provider, String token, MediaKind kind, long bytes, String format) {
    }

    /**
     * One story as a viewer sees it. View and like counts are only for the club's story managers (left out for
     * everyone else), and {@code club} is only set on the story just posted.
     */
    public record StoryCard(
            @JsonProperty("_id") String id,
            MediaKind kind,
            String url,
            String poster,
            String thumb,
            Integer width,
            Integer height,
            Double duration,
            String caption,
            Map<String, Object> event,
            Instant createdAt,
            Instant expiresAt,
            boolean seen,
            boolean liked,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer viewCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer likeCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> club) {
    }

    /** A club's ring in the story tray. */
    public record TrayGroup(Map<String, Object> club, boolean canManage, Instant latestAt, boolean allSeen, List<StoryCard> stories) {
    }

    public record Seen(boolean seen) {
    }

    public record Liked(boolean liked) {
    }

    /** Someone who watched a story, for its managers. */
    public record StoryViewer(Map<String, Object> user, Instant viewedAt, boolean liked) {
    }

    public record ViewersMeta(long total, int page, int limit, long totalPages, int viewCount, int likeCount) {
    }

    public record Viewers(List<StoryViewer> items, ViewersMeta meta) {
    }
}
