package com.campusconnect.feature.recruitment.drive;

import static com.campusconnect.feature.recruitment.drive.DrivePhases.names;

import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.feature.recruitment.mail.RecruitmentMailer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Completes a published drive once every role has had its final selection and no offer is waiting for an answer
 * (RecruitmentService.js completeIfDone): anyone still waiting is thanked. Roles that still have free seats and
 * reserves keep the drive open, so the president can make more offers (or close recruitment by hand). The drive is
 * claimed atomically, so when two last answers arrive together only one run sends the thank-you notes.
 */
@Service
public class DriveCompletion {

    private static final List<ApplicationStatus> LEFT_OVER = List.of(ApplicationStatus.APPLIED, ApplicationStatus.IN_ROUNDS, ApplicationStatus.RESERVE,
            ApplicationStatus.OFFERED);

    private final MongoOperations mongo;
    private final Drives drives;
    private final AuditService audit;
    private final NotificationService notifications;
    private final RecruitmentMailer mailer;

    public DriveCompletion(MongoOperations mongo, Drives drives, AuditService audit, NotificationService notifications, RecruitmentMailer mailer) {
        this.mongo = mongo;
        this.drives = drives;
        this.audit = audit;
        this.notifications = notifications;
        this.mailer = mailer;
    }

    public boolean completeIfDone(String driveId) {
        return completeIfDone(driveId, false, null);
    }

    /** {@code force}: the president closes recruitment by hand ({@code actor}), whatever is still open. */
    public boolean completeIfDone(String driveId, boolean force, AuthUser actor) {
        Query driveQuery = Query.query(Criteria.where("_id").is(new ObjectId(driveId)));
        driveQuery.fields().exclude("positions.form");
        RecruitmentDrive drive = mongo.findOne(driveQuery, RecruitmentDrive.class);
        if (drive == null || drive.getStatus() != RecruitmentStatus.PUBLISHED) {
            return false;
        }
        List<ApplicationStatus> wanted = new ArrayList<>(DrivePhases.OPEN_APPLICATION);
        wanted.add(ApplicationStatus.ACCEPTED);
        Query query = Query.query(Criteria.where("drive").is(new ObjectId(driveId)).and("status").in(names(wanted)));
        query.fields().include("position").include("status").include("applicant");
        List<RecruitmentApplication> applications = mongo.find(query, RecruitmentApplication.class);

        if (!force && !finished(drive, applications)) {
            return false;
        }
        Instant now = DrivePhases.now();
        if (!drives.update(driveId, Criteria.where("status").is(RecruitmentStatus.PUBLISHED.name()),
                new Update().set("status", RecruitmentStatus.COMPLETED.name()).set("completedAt", now))) {
            return false;
        }
        List<RecruitmentApplication> leftOver = applications.stream().filter(application -> LEFT_OVER.contains(application.getStatus())).toList();
        if (!leftOver.isEmpty()) {
            mongo.updateMulti(
                    Query.query(Criteria.where("_id").in(leftOver.stream().map(application -> new ObjectId(application.getId())).toList()).and("status").in(names(LEFT_OVER))),
                    new Update().set("status", ApplicationStatus.NOT_SELECTED.name()).set("decidedAt", now).set("slots", List.of()).set("updatedAt", now),
                    RecruitmentApplication.class);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", new ObjectId(drive.getClub()));
        metadata.put("closedByHand", force);
        audit.record(AuditAction.RECRUITMENT_COMPLETED, actor != null ? actor.id() : drive.getCreatedBy(), "RecruitmentDrive", driveId, null, null, null, metadata);

        Club club = mongo.findById(drive.getClub(), Club.class);
        if (club == null) {
            return true;
        }
        Map<String, List<String>> thanked = new LinkedHashMap<>();
        for (RecruitmentApplication application : leftOver) {
            String title = Objects.requireNonNullElse(Drives.positionTitle(drive, application.getPosition()), "the role");
            thanked.computeIfAbsent(title, unused -> new ArrayList<>()).add(application.getApplicant());
        }
        thanked.forEach((title, userIds) -> mailer.sendNotSelected(drive, club, userIds, title));
        long joined = applications.stream().filter(application -> application.getStatus() == ApplicationStatus.ACCEPTED).count();
        if (club.getMentor() != null) {
            notifications.notify(club.getMentor(), Notice.builder()
                    .type(NotificationType.RECRUITMENT_UPDATE)
                    .title(club.getName() + " finished recruiting")
                    .message(joined + " new " + (joined == 1 ? "member" : "members") + " joined through " + drive.getTitle() + ".")
                    .link(RecruitmentMailer.drivePath(drive))
                    .build());
        }
        return true;
    }

    /** Every role decided, no offer waiting, and no role with free seats that its reserve list could still fill. */
    private static boolean finished(RecruitmentDrive drive, List<RecruitmentApplication> applications) {
        if (!drive.getPositions().stream().allMatch(position -> position.getFinalizedAt() != null)) {
            return false;
        }
        if (applications.stream().anyMatch(application -> application.getStatus() == ApplicationStatus.OFFERED)) {
            return false;
        }
        for (RecruitmentPosition position : drive.getPositions()) {
            long reserve = count(applications, position, ApplicationStatus.RESERVE);
            if (reserve > 0 && (position.getOpenings() == null || position.getOpenings() == 0 || count(applications, position, ApplicationStatus.ACCEPTED) < position.getOpenings())) {
                return false;
            }
        }
        return true;
    }

    private static long count(List<RecruitmentApplication> applications, RecruitmentPosition position, ApplicationStatus status) {
        return applications.stream().filter(application -> position.getId().equals(application.getPosition()) && application.getStatus() == status).count();
    }
}
