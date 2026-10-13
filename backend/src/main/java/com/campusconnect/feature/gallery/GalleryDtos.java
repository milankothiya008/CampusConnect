package com.campusconnect.feature.gallery;

import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.event.GalleryStatus;
import com.campusconnect.domain.event.UploaderRole;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request bodies (routes/EventRoutes.js and GalleryRoutes.js rules) and views of the gallery routes. */
public final class GalleryDtos {

    private GalleryDtos() {
    }

    public record TicketsBody(
            @NotNull(message = "Choose up to 20 photos or videos at a time")
            @Size(min = 1, max = 20, message = "Choose up to 20 photos or videos at a time")
            List<@NotNull(message = "Only photos and videos can be added") @Pattern(regexp = "IMAGE|VIDEO", message = "Only photos and videos can be added") String> kinds) {
    }

    /** {@code media} is what the browser got back from the upload (Cloudinary's signed response or the local token). */
    public record AddBody(@NotNull(message = "Add a photo or video") Map<String, Object> media) {
    }

    public record ReviewBody(
            @NotNull(message = "Choose photos or videos")
            @Size(min = 1, max = 100, message = "Choose photos or videos")
            List<@NotNull(message = "Invalid selection") @Pattern(regexp = "(?i)^[a-f\\d]{24}$", message = "Invalid selection") String> ids,
            @Size(max = 200, message = "Keep the reason under 200 characters") String reason) {
    }

    public record PageQuery(String page, String limit) {
    }

    /** GET /gallery: which events to list ({@code show}: all, photos or review), for a club, matching a search. */
    public record GalleriesQuery(
            @Pattern(regexp = "all|photos|review", message = "Invalid value") String show,
            @Pattern(regexp = "^[0-9a-fA-F]{24}$", message = "Invalid value") String club,
            @Size(max = 100, message = "Invalid value") String search,
            String page,
            String limit) {
    }

    /** A file saved to development storage, ready to attach with its token. */
    public record LocalMedia(String provider, String token, MediaKind kind, long bytes, String format) {
    }

    /** One photo or video as the viewer sees it. */
    public record GalleryItem(
            @JsonProperty("_id") String id,
            MediaKind kind,
            String url,
            String poster,
            String thumb,
            Integer width,
            Integer height,
            Double duration,
            GalleryStatus status,
            Map<String, Object> uploader,
            UploaderRole uploaderRole,
            Instant createdAt,
            int likeCount,
            boolean likedByMe,
            boolean mine,
            boolean canDelete) {
    }

    public record Limits(long maxImageBytes, long maxVideoBytes, int maxVideoSeconds, int maxPendingPerUser) {
    }

    /**
     * What the viewer may do: {@code role} is how they may upload (MEMBER or PARTICIPANT); {@code hint} tells a
     * registered student what they still need to do, only while they can still do it; {@code reason} explains a
     * refused upload.
     */
    public record Access(boolean canUpload, boolean canModerate, String role, String hint, String reason) {
    }

    public record Viewer(boolean canUpload, boolean canModerate, String role, String hint, String reason, Limits limits) {
    }

    public record Counts(long approved, long pending) {
    }

    /** An event's gallery: approved items (paged), the viewer's own items waiting for review, and the review queue. */
    public record Gallery(List<GalleryItem> items, List<GalleryItem> mine, List<GalleryItem> pending, Counts counts, Viewer viewer) {
    }

    public record Approved(int approved, Counts counts) {
    }

    public record Rejected(int rejected, Counts counts) {
    }

    public record Removed(Counts counts) {
    }

    /** An event card in the Gallery section. */
    public record CardEvent(
            @JsonProperty("_id") String id,
            String title,
            String poster,
            String category,
            Instant startAt,
            Instant endAt,
            String status,
            Map<String, Object> club) {
    }

    public record GalleryCard(CardEvent event, long photos, long videos, Instant latestAt, long pending, String cover, List<String> previews) {
    }

    public record SectionCounts(long all, long photos, long review) {
    }

    public record GalleriesMeta(long total, int page, int limit, long totalPages, SectionCounts counts, boolean canReview) {
    }

    public record Galleries(List<GalleryCard> items, GalleriesMeta meta) {
    }
}
