package com.campusconnect.feature.recruitment.dto;

import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.PositionForm;
import com.campusconnect.domain.recruitment.RecruitmentEligibility;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.recruitment.RoundMode;
import com.campusconnect.domain.recruitment.RoundStatus;
import com.campusconnect.domain.recruitment.RoundTiming;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the drive pages read (RecruitmentService.js serializeDrive, positionView, listings). Fields only the club's
 * president and mentor see are left out of the JSON for everyone else, as before.
 */
public final class DriveViews {

    private DriveViews() {
    }

    public record ClubSummary(
            @JsonProperty("_id") String id,
            String name,
            String logo,
            String coverImage,
            String category,
            boolean allDepartments,
            List<String> departmentCodes) {
    }

    /** Applications of a role (or of the whole drive): all but withdrawn, still in the rounds, offered, joined, reserve. */
    public record Counts(int total, int active, int offered, int accepted, int reserve) {
    }

    public record DriveCounts(int total, int active, int offered, int accepted, int reserve, Map<String, Counts> byPosition) {
    }

    /** A round as anyone sees it, or with its schedule for the president and mentor. */
    public sealed interface RoundSummary permits PublicRound, StaffRound {
    }

    public record PublicRound(
            @JsonProperty("_id") String id,
            String name,
            RoundMode mode,
            RoundTiming timing,
            RoundStatus status,
            Instant resultsPublishedAt) implements RoundSummary {
    }

    public record StaffRound(
            @JsonProperty("_id") String id,
            String name,
            RoundMode mode,
            RoundTiming timing,
            RoundStatus status,
            Instant resultsPublishedAt,
            Instant startAt,
            Instant endAt,
            Integer slotMinutes,
            Map<String, Object> venue,
            String meetingLink,
            String instructions,
            Instant scheduledAt) implements RoundSummary {
    }

    public record PositionView(
            @JsonProperty("_id") String id,
            String role,
            String title,
            Integer openings,
            String description,
            PositionForm form,
            int questionCount,
            String stage,
            Instant finalizedAt,
            List<RoundSummary> rounds,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer offerDays,
            @JsonInclude(JsonInclude.Include.NON_NULL) Counts counts) {
    }

    public record ViewerApplication(
            @JsonProperty("_id") String id,
            String position,
            ApplicationStatus status,
            Instant createdAt,
            Instant offerExpiresAt) {
    }

    public record Viewer(
            boolean canManage,
            @JsonProperty("isMentor") boolean isMentor,
            boolean canReview,
            boolean canEdit,
            boolean canApply,
            String applyProblem,
            List<ViewerApplication> applications) {
    }

    /** The review trail and application counts, for the president and mentor only. */
    public record StaffFields(DriveCounts counts, Instant submittedAt, String reviewComment, Instant reviewedAt) {
    }

    public record DriveView(
            @JsonProperty("_id") String id,
            ClubSummary club,
            String title,
            String description,
            RecruitmentEligibility eligibility,
            Instant applicationStart,
            Instant applicationEnd,
            Instant closedAt,
            RecruitmentStatus status,
            String phase,
            Instant publishedAt,
            Instant completedAt,
            Instant cancelledAt,
            String cancellationReason,
            Instant createdAt,
            List<PositionView> positions,
            @JsonUnwrapped StaffFields staff,
            Viewer viewer) {
    }

    public record ClubDriveItem(
            @JsonProperty("_id") String id,
            String title,
            RecruitmentStatus status,
            String phase,
            List<String> positions,
            Instant applicationStart,
            Instant applicationEnd,
            Instant closedAt,
            int rounds,
            Instant completedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) DriveCounts counts) {
    }

    public record ClubDrives(boolean canCreate, List<ClubDriveItem> items) {
    }

    /** A drive taking applications, for the campus recruitment page; {@code eligible} is null for visitors. */
    public record OpenDriveItem(
            @JsonProperty("_id") String id,
            String title,
            ClubSummary club,
            List<String> positions,
            Instant applicationStart,
            Instant applicationEnd,
            Instant closedAt,
            String phase,
            Boolean eligible) {
    }

    /** A club's drive currently taking applications ("Recruiting" badges on club cards and pages). */
    public record OpenDrive(
            @JsonProperty("_id") String id,
            String title,
            Instant applicationStart,
            Instant applicationEnd,
            boolean open) {
    }

    /** A drive waiting for the mentor's review. */
    public record ReviewItem(
            @JsonProperty("_id") String id,
            String title,
            Map<String, Object> club,
            List<String> positions,
            int questions,
            Instant applicationEnd,
            Instant submittedAt) {
    }
}
