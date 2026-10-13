package com.campusconnect.feature.recruitment.drive;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.DepartmentScope;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Who may apply to a drive (RecruitmentService.js applyProblem): eligible students of the club's departments and batches. */
public final class ApplyRules {

    private ApplyRules() {
    }

    /**
     * Why this person can't apply to the drive right now, or null.
     *
     * @param isMember whether they already hold an approved membership of the club
     */
    public static String problem(AuthUser actor, RecruitmentDrive drive, Club club, boolean isMember, Instant now) {
        if (actor == null) {
            return "Sign in to apply";
        }
        if (!ClubAccess.isStudent(actor)) {
            return "Only students can apply";
        }
        if (drive.getStatus() != RecruitmentStatus.PUBLISHED) {
            return "This drive isn't accepting applications";
        }
        if (club.getStatus() != ClubStatus.ACTIVE) {
            return club.getName() + " is " + String.valueOf(club.getStatus()).toLowerCase(Locale.ROOT) + ", so recruitment is on hold";
        }
        if (isMember) {
            return "You're already a member of " + club.getName();
        }
        DepartmentScope.Scope scope = new DepartmentScope.Scope(club.isAllDepartments(), club.getDepartmentCodes() == null ? List.of() : club.getDepartmentCodes());
        if (!DepartmentScope.belongs(actor.departmentCode(), scope)) {
            return club.getName() + " recruits students from " + DepartmentScope.describe(scope);
        }
        List<String> batches = drive.getEligibility() == null ? List.of() : drive.getEligibility().getBatches();
        if (!batches.isEmpty() && !batches.contains(actor.batchCode())) {
            return "Open to batch " + batches.stream().map(batch -> "20" + batch).collect(Collectors.joining(", "));
        }
        String phase = DrivePhases.phaseOf(drive, now);
        if (DrivePhases.UPCOMING.equals(phase)) {
            return "Applications open " + CampusTime.formatDate(drive.getApplicationStart()) + " at " + CampusTime.formatTime(drive.getApplicationStart());
        }
        if (!DrivePhases.OPEN.equals(phase)) {
            return "Applications are closed";
        }
        return null;
    }
}
