package com.campusconnect.feature.recruitment.application;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.events.ClubMembershipChanged;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.AuthUserService;
import com.campusconnect.core.util.Phones;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.recruitment.drive.DriveCompletion;
import com.campusconnect.feature.recruitment.drive.DrivePhases;
import com.campusconnect.feature.recruitment.drive.Drives;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.MyApplication;
import com.campusconnect.feature.recruitment.mail.RecruitmentMailer;
import com.campusconnect.feature.recruitment.support.Js;
import com.campusconnect.feature.recruitment.support.RecruitmentLocks;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import static com.campusconnect.feature.recruitment.application.ApplicationService.TARGET;
import static com.campusconnect.feature.recruitment.application.ApplicationService.oid;
import static com.campusconnect.feature.recruitment.drive.DrivePhases.OPEN_APPLICATION;
import static com.campusconnect.feature.recruitment.drive.DrivePhases.names;
import static com.campusconnect.feature.recruitment.drive.Drives.invalidState;

/**
 * A student answers an offer (ApplicationService.js respondToOffer). Accepting makes them a member in that role and
 * closes every other application they have in the drive (they can hold one role); declining frees the seat.
 * Accepting is atomic: the offer is claimed once (a second click finds it answered), a role never gets more
 * members than openings (seats are counted and taken under a per-role lock), and the membership itself is only
 * created if the student is not a member already (so two offers accepted at once leave them in one role).
 */
@Service
public class OfferService {

    private static final String NO_LONGER_OPEN = "This offer is no longer open";
    private static final String VICE_SEAT_FILLED = "The vice-president seat has already been filled";

    private final MongoOperations mongo;
    private final Drives drives;
    private final ClubAccess clubAccess;
    private final AuditService audit;
    private final NotificationService notifications;
    private final RecruitmentMailer mailer;
    private final DriveCompletion completion;
    private final ApplicationService applications;
    private final AuthUserService authUsers;
    private final RecruitmentLocks locks;

    private final ApplicationEventPublisher events;

    public OfferService(MongoOperations mongo, Drives drives, ClubAccess clubAccess, AuditService audit, NotificationService notifications, RecruitmentMailer mailer,
            DriveCompletion completion, ApplicationService applications, AuthUserService authUsers, RecruitmentLocks locks, ApplicationEventPublisher events) {
        this.events = events;
        this.mongo = mongo;
        this.drives = drives;
        this.clubAccess = clubAccess;
        this.audit = audit;
        this.notifications = notifications;
        this.mailer = mailer;
        this.completion = completion;
        this.applications = applications;
        this.authUsers = authUsers;
        this.locks = locks;
    }

    public List<MyApplication> accept(AuthUser actor, String driveId, String applicationId, Object phone) {
        return respond(actor, driveId, applicationId, true, phone);
    }

    public List<MyApplication> decline(AuthUser actor, String driveId, String applicationId) {
        return respond(actor, driveId, applicationId, false, null);
    }

    private static Query offered(String applicationId) {
        return Query.query(Criteria.where("_id").is(oid(applicationId)).and("status").is(ApplicationStatus.OFFERED.name()));
    }

    private List<MyApplication> respond(AuthUser actor, String driveId, String applicationId, boolean accept, Object phone) {
        RecruitmentDrive drive = drives.load(driveId);
        Club club = clubAccess.loadClub(drive.getClub());
        RecruitmentApplication application = mongo.findOne(
                Query.query(Criteria.where("_id").is(oid(applicationId)).and("drive").is(oid(drive.getId())).and("applicant").is(oid(actor.id()))),
                RecruitmentApplication.class);
        if (application == null || application.getStatus() != ApplicationStatus.OFFERED) {
            throw invalidState(NO_LONGER_OPEN);
        }
        RecruitmentPosition position = Drives.position(drive, application.getPosition());
        Instant now = DrivePhases.now();
        if (application.getOfferExpiresAt() != null && !application.getOfferExpiresAt().isAfter(now)) {
            mongo.updateFirst(offered(applicationId), new Update().set("status", ApplicationStatus.EXPIRED.name()).set("updatedAt", now), RecruitmentApplication.class);
            throw invalidState("This offer has expired");
        }
        List<String> presidents = clubAccess.usersWithPermission(club.getId(), ClubPermission.MANAGE_RECRUITMENT);
        String link = "/recruitment/" + drive.getId() + "?tab=selection&role=" + position.getId();

        if (!accept) {
            if (mongo.updateFirst(offered(applicationId), new Update().set("status", ApplicationStatus.DECLINED.name()).set("respondedAt", now).set("updatedAt", now),
                    RecruitmentApplication.class).getModifiedCount() == 0) {
                throw invalidState(NO_LONGER_OPEN);
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("driveId", oid(drive.getId()));
            metadata.put("role", position.getRole());
            audit.record(AuditAction.OFFER_DECLINED, actor.id(), TARGET, applicationId, metadata);
            notifications.notify(presidents, Notice.builder()
                    .type(NotificationType.RECRUITMENT_UPDATE)
                    .title("Offer declined: " + position.getTitle())
                    .message(actor.name() + " declined the " + position.getTitle() + " offer. You can offer the seat to a reserve candidate.")
                    .link(link)
                    .build());
            completion.completeIfDone(drive.getId());
            return applications.getMyApplications(actor, drive.getId());
        }

        if (club.getStatus() != ClubStatus.ACTIVE) {
            throw invalidState(club.getName() + " is " + String.valueOf(club.getStatus()).toLowerCase(Locale.ROOT)
                    + ", so offers are on hold. Your offer is kept and its deadline moves on if the club is reactivated.");
        }
        if (clubAccess.membership(actor.id(), club.getId()) != null) {
            throw invalidState("You're already a member of " + club.getName());
        }
        if (ClubRoles.findRole(club, position.getRole()) == null) {
            throw invalidState(club.getName() + " no longer has the " + position.getTitle() + " role. Contact the club.");
        }
        boolean vice = ClubRoles.VICE_PRESIDENT.equals(position.getRole());
        if (vice && viceTaken(club)) {
            throw invalidState(VICE_SEAT_FILLED);
        }
        ensurePhone(actor, phone);
        if (position.getOpenings() != null) {
            locks.withPositionLock(position.getId(), () -> {
                long accepted = mongo.count(Query.query(Criteria.where("drive").is(oid(drive.getId())).and("position").is(oid(position.getId()))
                        .and("status").is(ApplicationStatus.ACCEPTED.name())), RecruitmentApplication.class);
                if (accepted >= position.getOpenings()) {
                    throw invalidState(position.getOpenings() == 1
                            ? (vice ? VICE_SEAT_FILLED : "The " + position.getTitle() + " seat has already been filled")
                            : "All " + position.getOpenings() + " " + position.getTitle() + " seats have already been filled");
                }
                join(application, club, position, now);
                return null;
            });
        } else {
            join(application, club, position, now);
        }

        // One role per student: everything else they have in this drive closes now.
        Query othersQuery = Query.query(Criteria.where("drive").is(oid(drive.getId())).and("applicant").is(oid(actor.id())).and("_id").ne(oid(applicationId))
                .and("status").in(names(OPEN_APPLICATION)));
        othersQuery.fields().include("position").include("status");
        List<RecruitmentApplication> others = mongo.find(othersQuery, RecruitmentApplication.class);
        List<String> closedTitles = others.stream().map(other -> Drives.positionTitle(drive, other.getPosition())).filter(Objects::nonNull).toList();
        List<RecruitmentApplication> freedOffers = others.stream().filter(other -> other.getStatus() == ApplicationStatus.OFFERED).toList();
        if (!others.isEmpty()) {
            mongo.updateMulti(
                    Query.query(Criteria.where("_id").in(others.stream().map(other -> oid(other.getId())).toList()).and("status").in(names(OPEN_APPLICATION))),
                    new Update().set("status", ApplicationStatus.WITHDRAWN.name()).set("withdrawnAt", now).set("slots", List.of())
                            .set("closedReason", "Joined as " + position.getTitle()).set("pendingOutcome", ApplicationService.clearedOutcome()).set("updatedAt", now),
                    RecruitmentApplication.class);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("driveId", oid(drive.getId()));
        metadata.put("clubId", oid(club.getId()));
        metadata.put("role", position.getRole());
        metadata.put("closed", others.size());
        audit.record(AuditAction.OFFER_ACCEPTED, actor.id(), TARGET, applicationId, metadata);
        mailer.sendWelcome(drive, club, actor.id(), position.getTitle(), closedTitles);
        notifications.notify(presidents, Notice.builder()
                .type(NotificationType.RECRUITMENT_UPDATE)
                .title(actor.name() + " joined as " + position.getTitle())
                .message(freedOffers.isEmpty()
                        ? "They accepted your offer for " + drive.getTitle() + "."
                        : "They accepted the " + position.getTitle() + " offer, so their "
                                + freedOffers.stream().map(other -> Objects.requireNonNullElse(Drives.positionTitle(drive, other.getPosition()), "")).collect(Collectors.joining(", "))
                                + " offer is free again.")
                .link(link)
                .build());
        completion.completeIfDone(drive.getId());
        return applications.getMyApplications(actor, drive.getId());
    }

    private boolean viceTaken(Club club) {
        return mongo.exists(Query.query(Criteria.where("club").is(oid(club.getId())).and("role").is(ClubRoles.VICE_PRESIDENT).and("status").is(MembershipStatus.APPROVED.name())),
                ClubMembership.class);
    }

    /**
     * Claims the offer, then makes the student a member in the role. The membership is written only if they are not
     * an approved member already; when that (or the vice-president seat) fails, the offer goes back as it was.
     */
    private void join(RecruitmentApplication application, Club club, RecruitmentPosition position, Instant now) {
        RecruitmentApplication claimed = mongo.findAndModify(offered(application.getId()),
                new Update().set("status", ApplicationStatus.ACCEPTED.name()).set("respondedAt", now).set("decidedAt", now).set("updatedAt", now),
                FindAndModifyOptions.options().returnNew(true), RecruitmentApplication.class);
        if (claimed == null) {
            throw invalidState(NO_LONGER_OPEN);
        }
        Query membership = Query.query(Criteria.where("club").is(oid(club.getId())).and("user").is(oid(application.getApplicant()))
                .and("status").ne(MembershipStatus.APPROVED.name()));
        Update update = new Update().set("status", MembershipStatus.APPROVED.name()).set("role", position.getRole()).set("joinedAt", now).set("decidedAt", now)
                .set("decisionReason", null).set("updatedAt", now).setOnInsert("createdAt", now);
        try {
            mongo.upsert(membership, update, ClubMembership.class);
            // The new member is in the club group chat at once (it reads a cached member list).
            events.publishEvent(new ClubMembershipChanged(club.getId(), application.getApplicant()));
        } catch (DuplicateKeyException e) {
            restore(application);
            throw invalidState(clubAccess.membership(application.getApplicant(), club.getId()) != null ? "You're already a member of " + club.getName() : VICE_SEAT_FILLED);
        } catch (RuntimeException e) {
            restore(application);
            throw e;
        }
    }

    /** Puts a claimed offer back to OFFERED with its earlier answer and decision times. */
    private void restore(RecruitmentApplication application) {
        Update update = new Update().set("status", ApplicationStatus.OFFERED.name()).set("respondedAt", application.getRespondedAt()).set("decidedAt", application.getDecidedAt())
                .set("updatedAt", Instant.now());
        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(application.getId())).and("status").is(ApplicationStatus.ACCEPTED.name())), update, RecruitmentApplication.class);
    }

    /** Club members must have a mobile number on file; it can be given with the acceptance itself. */
    private void ensurePhone(AuthUser actor, Object phone) {
        String current = actor.phone();
        if (current == null || current.isEmpty()) {
            Query query = Query.query(Criteria.where("_id").is(oid(actor.id())));
            query.fields().include("phone");
            Document user = mongo.findOne(query, Document.class, "users");
            current = user == null ? null : user.getString("phone");
        }
        if (current != null && !current.isEmpty()) {
            return;
        }
        boolean given = Js.truthy(phone);
        String normalized = given ? Phones.normalize(Js.string(phone)) : null;
        if (normalized == null) {
            throw given
                    ? AppException.badRequest("Enter a valid 10-digit Indian mobile number", ErrorCode.VALIDATION_ERROR)
                    : AppException.badRequest("Add your mobile number to join the club", ErrorCode.PHONE_REQUIRED);
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(actor.id()))), new Update().set("phone", normalized).set("updatedAt", Instant.now()), User.class);
        authUsers.evict(actor.id());
    }
}
