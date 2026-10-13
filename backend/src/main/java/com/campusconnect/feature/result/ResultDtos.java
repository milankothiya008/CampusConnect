package com.campusconnect.feature.result;

import com.campusconnect.core.web.validation.TrimmedSize;
import com.campusconnect.domain.result.ResultStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request bodies of the results routes (validators/RequestValidators.js resultRules and roundRules, same messages)
 * and the JSON views the results pages read. A {@code null} body field means "not sent".
 *
 * <p>Where express-validator reports two messages for a missing field (the bare {@code isString()} "Invalid value"
 * plus the length message), the {@code @NotNull} message carries both joined, so the response text is identical.
 */
public final class ResultDtos {

    /** An ObjectId, or empty: {@code optional({values: "falsy"}).isMongoId()}. */
    static final String MONGO_ID_OR_EMPTY = "^([a-fA-F0-9]{24})?$";

    private ResultDtos() {
    }

    // ---------------------------------------------------------------- requests

    public record AwardBody(
            @NotNull(message = "Invalid value. Each award needs a title")
            @TrimmedSize(min = 1, max = 120, optional = true, message = "Each award needs a title") String title,
            @WholeNumber(min = 1, max = 1000) Number position,
            @Pattern(regexp = MONGO_ID_OR_EMPTY, message = "Invalid value") String recipientUser,
            @Size(max = 120, message = "Invalid value") String recipientName,
            @Size(max = 120, message = "Invalid value") String teamName,
            @Size(max = 200, message = "Invalid value") String prize,
            @Size(max = 300, message = "Invalid value") String recognition) {
    }

    /** PUT /api/events/:id/results — the final results draft (or a correction once published). */
    public record ResultBody(
            @NotNull(message = "Invalid value. Result summary is required")
            @TrimmedSize(min = 5, max = 4000, optional = true, message = "Result summary is required") String summary,
            @Size(max = 50, message = "Invalid value") List<@Valid AwardBody> awards) {
    }

    public record EntryBody(
            @WholeNumber(min = 1, max = 100000, message = "Rank must be a positive whole number") Number rank,
            @Pattern(regexp = MONGO_ID_OR_EMPTY, message = "Invalid value") String recipientUser,
            @Size(max = 120, message = "Invalid value") String recipientName,
            @Size(max = 120, message = "Invalid value") String teamName,
            @Size(max = 40, message = "Score must be at most 40 characters") String score,
            Boolean qualified,
            @Size(max = 200, message = "Invalid value") String note) {
    }

    /** POST /api/events/:id/results/rounds — roundRules(false). */
    public record NewRoundBody(
            @NotNull(message = "Invalid value. Round name must be 1-80 characters")
            @TrimmedSize(min = 1, max = 80, optional = true, message = "Round name must be 1-80 characters") String name,
            @Size(max = 1000, message = "Invalid value") String description,
            @Size(max = 500, message = "Entries must be a list of at most 500 rows") List<@Valid EntryBody> entries) {
    }

    /** PUT /api/events/:id/results/rounds/:roundId — roundRules(true): every field may be left out. */
    public record RoundChangesBody(
            @TrimmedSize(min = 1, max = 80, optional = true, message = "Round name must be 1-80 characters") String name,
            @Size(max = 1000, message = "Invalid value") String description,
            @Size(max = 500, message = "Entries must be a list of at most 500 rows") List<@Valid EntryBody> entries) {
    }

    // ---------------------------------------------------------------- views

    /** One line of a round's standings; {@code recipientUser} is populated with "name departmentCode batchCode". */
    public record EntryView(
            @JsonProperty("_id") String id,
            Integer rank,
            Map<String, Object> recipientUser,
            String recipientName,
            String teamName,
            String score,
            Boolean qualified,
            String note) {
    }

    public record RoundView(
            @JsonProperty("_id") String id,
            String name,
            String description,
            List<EntryView> entries,
            ResultStatus status,
            Instant publishedAt,
            String publishedBy,
            Instant correctedAt,
            String createdBy,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** An award; {@code recipientUser} is populated with "name email departmentCode batchCode". */
    public record AwardView(
            @JsonProperty("_id") String id,
            String title,
            Integer position,
            String recipientName,
            Map<String, Object> recipientUser,
            String teamName,
            String prize,
            String recognition) {
    }

    /** What the viewer may do with these results. */
    public record Viewer(boolean canSeeDrafts, boolean canEdit, boolean canPublish) {
    }

    /** GET /api/events/:id/results (ResultService.shapeResult). */
    public record ResultView(
            @JsonProperty("_id") String id,
            Map<String, Object> event,
            ResultStatus status,
            String summary,
            List<AwardView> awards,
            Instant publishedAt,
            Instant correctedAt,
            Instant lastPublishedAt,
            Map<String, Object> createdBy,
            Instant updatedAt,
            List<RoundView> rounds,
            Viewer viewer) {
    }

    public record LatestRound(String name, Instant publishedAt) {
    }

    public record RoundsSummary(int published, LatestRound latest) {
    }

    /** One card of the Results page. */
    public record ResultCard(
            @JsonProperty("_id") String id,
            Map<String, Object> event,
            @JsonProperty("final") boolean isFinal,
            Instant publishedAt,
            Instant lastPublishedAt,
            List<AwardView> winners,
            RoundsSummary rounds) {
    }

    public record Counts(long all, @JsonProperty("final") long finalCount, long live) {
    }

    /** The list's {@code meta}: stage counts plus the usual pagination fields. */
    public record BoardMeta(Counts counts, long total, int page, int limit, long totalPages) {
    }

    /** A page of the Results page (cached briefly in Redis). */
    public record Board(List<ResultCard> items, BoardMeta meta) {
    }
}
