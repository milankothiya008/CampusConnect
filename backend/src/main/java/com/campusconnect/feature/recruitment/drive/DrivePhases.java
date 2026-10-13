package com.campusconnect.feature.recruitment.drive;

import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentQuestion;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;

/**
 * Where a drive and each of its roles stand (RecruitmentService.js phaseOf / stageOf). Phases follow the clock, so
 * they are worked out on every read rather than stored.
 */
public final class DrivePhases {

    public static final String UPCOMING = "UPCOMING";
    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";
    public static final String ROUNDS = "ROUNDS";

    /** Statuses in which the president can still edit the drive. */
    public static final List<RecruitmentStatus> EDITABLE = List.of(RecruitmentStatus.DRAFT, RecruitmentStatus.NEEDS_CHANGES);
    /** A club runs one drive at a time. */
    public static final List<RecruitmentStatus> ACTIVE = List.of(RecruitmentStatus.DRAFT, RecruitmentStatus.PENDING_APPROVAL, RecruitmentStatus.NEEDS_CHANGES,
            RecruitmentStatus.APPROVED, RecruitmentStatus.PUBLISHED);
    /** Applications still in play in the rounds. */
    public static final List<ApplicationStatus> ACTIVE_APPLICATION = List.of(ApplicationStatus.APPLIED, ApplicationStatus.IN_ROUNDS);
    /** Applications that are not over yet (rounds, offers, reserve list). */
    public static final List<ApplicationStatus> OPEN_APPLICATION = List.of(ApplicationStatus.APPLIED, ApplicationStatus.IN_ROUNDS, ApplicationStatus.OFFERED,
            ApplicationStatus.RESERVE);

    private DrivePhases() {
    }

    /** Timestamps as MongoDB keeps them (milliseconds), so a value read back equals the value returned now. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * A published drive is UPCOMING (applications not open yet), OPEN, CLOSED (deadline passed or closed early, no
     * role in rounds yet) or ROUNDS. Other statuses map to themselves.
     */
    public static String phaseOf(RecruitmentDrive drive, Instant now) {
        if (drive.getStatus() != RecruitmentStatus.PUBLISHED) {
            return drive.getStatus().name();
        }
        if (now.isBefore(orEpoch(drive.getApplicationStart()))) {
            return UPCOMING;
        }
        if (drive.getClosedAt() == null && now.isBefore(orEpoch(drive.getApplicationEnd()))) {
            return OPEN;
        }
        return selectionStarted(drive) ? ROUNDS : CLOSED;
    }

    public static boolean applicationsOpen(RecruitmentDrive drive, Instant now) {
        return OPEN.equals(phaseOf(drive, now));
    }

    /** Applications have not closed yet (they may not have opened either). */
    public static boolean takingApplications(RecruitmentDrive drive, Instant now) {
        String phase = phaseOf(drive, now);
        return UPCOMING.equals(phase) || OPEN.equals(phase);
    }

    /** Some role has rounds or its final selection: the deadline can no longer move. */
    public static boolean selectionStarted(RecruitmentDrive drive) {
        return drive.getPositions().stream().anyMatch(position -> !position.getRounds().isEmpty() || position.getFinalizedAt() != null);
    }

    /** A role's own stage once applications have closed: rounds, final selection done. */
    public static String stageOf(RecruitmentDrive drive, RecruitmentPosition position, Instant now) {
        if (position.getFinalizedAt() != null) {
            return "FINALIZED";
        }
        if (!position.getRounds().isEmpty()) {
            return ROUNDS;
        }
        if (takingApplications(drive, now)) {
            return "APPLICATIONS";
        }
        return drive.getStatus() == RecruitmentStatus.PUBLISHED ? CLOSED : drive.getStatus().name();
    }

    /** All questions of a role's form, across its pages. */
    public static List<RecruitmentQuestion> questionsOf(RecruitmentPosition position) {
        return position.getForm().getPages().stream().flatMap(page -> page.getQuestions().stream()).toList();
    }

    public static List<String> names(Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }

    private static Instant orEpoch(Instant value) {
        return value == null ? Instant.EPOCH : value;
    }
}
