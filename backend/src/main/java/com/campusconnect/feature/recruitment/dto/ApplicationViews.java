package com.campusconnect.feature.recruitment.dto;

import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.InterviewSlot;
import com.campusconnect.domain.recruitment.PendingOutcome;
import com.campusconnect.domain.recruitment.QuestionType;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.recruitment.RoundMode;
import com.campusconnect.domain.recruitment.RoundOutcome;
import com.campusconnect.domain.recruitment.RoundResult;
import com.campusconnect.domain.recruitment.RoundStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Applications as the applicant and the club see them (ApplicationService.js views). File answers carry a link
 * from the media store; documents get a signed link that expires, so a resume stays with whoever was shown it.
 */
public final class ApplicationViews {

    private ApplicationViews() {
    }

    public record FileView(String name, String format, Long bytes, MediaKind kind, String url) {
    }

    public record AnswerView(String question, String label, QuestionType type, String text, List<String> choices, FileView file) {
    }

    /** Answers grouped by the role form's pages. */
    public record AnswerPage(@JsonProperty("_id") String id, String title, List<AnswerView> answers) {
    }

    public record SlotTime(Instant startAt, Instant endAt) {
    }

    public record Place(String name, String location) {
    }

    /** A round as the applicant sees it: ones with a result, plus the round in progress. */
    public record ApplicantRound(
            @JsonProperty("_id") String id,
            String name,
            RoundMode mode,
            RoundStatus status,
            RoundOutcome result,
            SlotTime slot,
            Place venue,
            String meetingLink,
            String instructions) {
    }

    public record MyApplication(
            @JsonProperty("_id") String id,
            String position,
            String positionTitle,
            ApplicationStatus status,
            List<AnswerPage> pages,
            List<ApplicantRound> rounds,
            Instant offeredAt,
            Instant offerExpiresAt,
            Instant respondedAt,
            String closedReason,
            Instant createdAt,
            Instant updatedAt,
            boolean canEdit,
            boolean canWithdraw) {
    }

    public record DriveBrief(
            @JsonProperty("_id") String id,
            String title,
            RecruitmentStatus status,
            String phase,
            Instant applicationEnd,
            Map<String, Object> club) {
    }

    /** One row of "My applications" across drives, with what's next. */
    public record MyApplicationRow(
            @JsonProperty("_id") String id,
            ApplicationStatus status,
            Instant createdAt,
            String position,
            String positionTitle,
            Instant offerExpiresAt,
            String closedReason,
            DriveBrief drive,
            int roundsPassed,
            ApplicantRound nextInterview) {
    }

    /** What the president and mentor see of an application (never its answers in lists). */
    public record StaffApplication(
            @JsonProperty("_id") String id,
            Map<String, Object> applicant,
            ApplicationStatus status,
            String position,
            String positionTitle,
            List<RoundResult> roundResults,
            PendingOutcome pendingOutcome,
            List<InterviewSlot> slots,
            Instant offerExpiresAt,
            String closedReason,
            Instant createdAt) {
    }

    /** {@code otherRoles}: how many other roles the student applied for in this drive. */
    public record StaffApplicationRow(@JsonUnwrapped StaffApplication application, int otherRoles) {
    }

    public record OtherApplication(String position, String positionTitle, ApplicationStatus status) {
    }

    public record StaffApplicationDetail(@JsonUnwrapped StaffApplication application, List<AnswerPage> pages, List<OtherApplication> otherApplications) {
    }

    /** A file saved by this API in development storage, ready to attach to an answer. */
    public record LocalUpload(String provider, String token, MediaKind kind, long bytes, String format) {
    }
}
