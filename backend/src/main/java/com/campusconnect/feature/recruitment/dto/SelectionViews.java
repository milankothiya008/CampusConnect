package com.campusconnect.feature.recruitment.dto;

import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RoundMode;
import com.campusconnect.domain.recruitment.RoundOutcome;
import com.campusconnect.domain.recruitment.RoundStatus;
import com.campusconnect.domain.recruitment.RoundTiming;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.SlotTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The Selection tab of one role (RecruitmentRoundService.js getRounds): rounds with candidates, final selection, offers. */
public final class SelectionViews {

    private SelectionViews() {
    }

    public record PositionSummary(
            @JsonProperty("_id") String id,
            String title,
            String role,
            Integer openings,
            Instant finalizedAt,
            int offerDays,
            String stage) {
    }

    /** {@code outcome}/{@code note}: the published result, or the president's draft until it is published. */
    public record Candidate(
            String applicationId,
            Map<String, Object> applicant,
            RoundOutcome outcome,
            String note,
            boolean published,
            SlotTime slot) {
    }

    public record RoundDetail(
            @JsonProperty("_id") String id,
            String name,
            RoundMode mode,
            RoundTiming timing,
            Instant startAt,
            Instant endAt,
            Integer slotMinutes,
            Map<String, Object> venue,
            String meetingLink,
            String instructions,
            RoundStatus status,
            Instant scheduledAt,
            Instant resultsPublishedAt,
            Instant createdAt,
            Instant updatedAt,
            @JsonProperty("isCurrent") boolean isCurrent,
            List<Candidate> candidates) {
    }

    public record Finalist(String applicationId, Map<String, Object> applicant) {
    }

    public record Offer(
            String applicationId,
            Map<String, Object> applicant,
            ApplicationStatus status,
            Instant offeredAt,
            Instant offerExpiresAt,
            Instant respondedAt) {
    }

    /** {@code open} is null when the role has no fixed number of openings. */
    public record Seats(Integer openings, int accepted, int pending, Integer open) {
    }

    public record Selection(
            String phase,
            PositionSummary position,
            List<RoundDetail> rounds,
            int activeCount,
            boolean canAddRound,
            boolean canFinalize,
            List<Finalist> finalists,
            List<Offer> offers,
            Seats seats,
            boolean canOfferReserve) {
    }

    /** A candidate whose interview overlaps one for another role of the drive. */
    public record Warning(String applicationId, String clashWith, String at) {
    }

    /** The Selection tab after scheduling or moving a slot, with any clashes left over. */
    public record ScheduledSelection(@JsonUnwrapped Selection selection, List<Warning> warnings) {
    }
}
