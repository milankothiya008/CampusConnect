package com.campusconnect.feature.recruitment.reminder;

import static com.campusconnect.feature.recruitment.drive.DrivePhases.ACTIVE_APPLICATION;
import static com.campusconnect.feature.recruitment.drive.DrivePhases.names;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.InterviewSlot;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentRound;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.recruitment.RoundStatus;
import com.campusconnect.domain.recruitment.SentInterviewReminder;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.feature.recruitment.drive.DriveCompletion;
import com.campusconnect.feature.recruitment.drive.Drives;
import com.campusconnect.feature.recruitment.mail.RecruitmentMailer;
import com.campusconnect.feature.recruitment.mail.RecruitmentMailer.Slot;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Interview reminders and offer deadlines (services/RecruitmentReminderService.js). A sweep runs every minute on one
 * replica (ShedLock): reminders go out 1 hour and 10 minutes before each candidate's time, each claimed atomically on
 * the application before it is sent, so restarts or several servers never send it twice; a candidate scheduled at
 * short notice simply gets the reminders that still make sense. Offers not answered by their deadline expire, and
 * the president can then offer the seat to a reserve. Suspended and archived clubs are skipped.
 */
@Slf4j
@Service
public class RecruitmentReminders {

    private final MongoOperations mongo;
    private final PausedClubs pausedClubs;
    private final ClubAccess clubAccess;
    private final Refs refs;
    private final AuditService audit;
    private final NotificationService notifications;
    private final RecruitmentMailer mailer;
    private final DriveCompletion completion;

    public RecruitmentReminders(MongoOperations mongo, PausedClubs pausedClubs, ClubAccess clubAccess, Refs refs, AuditService audit, NotificationService notifications,
            RecruitmentMailer mailer, DriveCompletion completion) {
        this.mongo = mongo;
        this.pausedClubs = pausedClubs;
        this.clubAccess = clubAccess;
        this.refs = refs;
        this.audit = audit;
        this.notifications = notifications;
        this.mailer = mailer;
        this.completion = completion;
    }

    /** Which reminder is due: ("60m", 60) with 15–60 minutes left, ("10m", 10) with up to 10 minutes left. */
    public record Reminder(String kind, int minutes) {
    }

    public static Reminder reminderFor(Instant startAt, Instant now) {
        double left = (startAt.toEpochMilli() - now.toEpochMilli()) / 60_000d;
        if (left > 15 && left <= 60) {
            return new Reminder("60m", 60);
        }
        if (left > 0 && left <= 10) {
            return new Reminder("10m", 10);
        }
        return null;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    @SchedulerLock(name = "recruitmentSweep", lockAtMostFor = "PT5M")
    public void sweep() {
        try {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            int sent = sweepInterviewReminders(now);
            int expired = expireOffers(now);
            if (sent > 0 || expired > 0) {
                log.info("Recruitment sweep: {} reminders, {} expired offers", sent, expired);
            }
        } catch (RuntimeException e) {
            log.error("Interview reminder sweep failed: {}", e.getMessage());
        }
    }

    private static List<ObjectId> objectIds(Set<String> ids) {
        return ids.stream().map(ObjectId::new).toList();
    }

    /** Sends the reminders due at {@code now}; returns how many went out. */
    public int sweepInterviewReminders(Instant now) {
        Query query = Query.query(Criteria.where("status").in(names(ACTIVE_APPLICATION))
                .and("slots").elemMatch(Criteria.where("startAt").gt(now).lte(now.plus(Duration.ofMinutes(60)))));
        query.fields().include("drive").include("position").include("applicant").include("slots");
        List<RecruitmentApplication> applications = mongo.find(query, RecruitmentApplication.class);
        if (applications.isEmpty()) {
            return 0;
        }
        Set<String> paused = pausedClubs.ids();
        Criteria driveFilter = Criteria.where("_id").in(applications.stream().map(RecruitmentApplication::getDrive).distinct().map(ObjectId::new).toList())
                .and("status").is(RecruitmentStatus.PUBLISHED.name());
        if (!paused.isEmpty()) {
            driveFilter = driveFilter.and("club").nin(objectIds(paused));
        }
        Query driveQuery = Query.query(driveFilter);
        driveQuery.fields().exclude("positions.form").exclude("description");
        Map<String, RecruitmentDrive> drives = mongo.find(driveQuery, RecruitmentDrive.class).stream().collect(Collectors.toMap(RecruitmentDrive::getId, Function.identity()));
        Map<String, Club> clubs = clubNames(drives.values().stream().map(RecruitmentDrive::getClub).collect(Collectors.toSet()));
        Map<String, Venue> venues = new HashMap<>();
        int sent = 0;
        for (RecruitmentApplication application : applications) {
            RecruitmentDrive drive = drives.get(application.getDrive());
            RecruitmentPosition position = drive == null ? null : Drives.positionOrNull(drive, application.getPosition());
            Club club = drive == null ? null : clubs.get(drive.getClub());
            if (position == null || club == null) {
                continue;
            }
            for (InterviewSlot slot : application.getSlots()) {
                RecruitmentRound round = position.getRounds().stream().filter(item -> item.getId().equals(slot.getRound())).findFirst().orElse(null);
                Reminder reminder = slot.getStartAt() == null ? null : reminderFor(slot.getStartAt(), now);
                if (round == null || round.getStatus() != RoundStatus.SCHEDULED || reminder == null) {
                    continue;
                }
                // Claim it: only one sweep can add this (round, kind) pair.
                Query claim = Query.query(Criteria.where("_id").is(new ObjectId(application.getId()))
                        .and("remindersSent").not().elemMatch(Criteria.where("round").is(new ObjectId(round.getId())).and("kind").is(reminder.kind())));
                Update update = new Update().push("remindersSent", new SentInterviewReminder(round.getId(), reminder.kind(), now)).set("updatedAt", now);
                if (mongo.updateFirst(claim, update, RecruitmentApplication.class).getModifiedCount() == 0) {
                    continue;
                }
                Venue venue = round.getVenue() == null ? null : venues.computeIfAbsent(round.getVenue(), id -> mongo.findById(id, Venue.class));
                mailer.sendInterviewReminder(drive, club, position, round, new Slot(slot.getStartAt(), slot.getEndAt()), application.getApplicant(), venue, reminder.minutes());
                sent++;
            }
        }
        return sent;
    }

    private Map<String, Club> clubNames(Set<String> ids) {
        Query query = Query.query(Criteria.where("_id").in(ids.stream().filter(Objects::nonNull).map(ObjectId::new).toList()));
        query.fields().include("name");
        return mongo.find(query, Club.class).stream().collect(Collectors.toMap(Club::getId, Function.identity()));
    }

    /** Offers not answered by their deadline expire; returns how many offers were past it. */
    public int expireOffers(Instant now) {
        Set<String> paused = pausedClubs.ids();
        Criteria filter = Criteria.where("status").is(ApplicationStatus.OFFERED.name()).and("offerExpiresAt").lte(now);
        if (!paused.isEmpty()) {
            filter = filter.and("club").nin(objectIds(paused));
        }
        Query query = Query.query(filter);
        query.fields().include("drive").include("position").include("applicant").include("club");
        List<RecruitmentApplication> expired = mongo.find(query, RecruitmentApplication.class);
        if (expired.isEmpty()) {
            return 0;
        }
        Query driveQuery = Query.query(Criteria.where("_id").in(expired.stream().map(RecruitmentApplication::getDrive).distinct().map(ObjectId::new).toList()));
        driveQuery.fields().exclude("positions.form").exclude("description");
        Map<String, RecruitmentDrive> drives = mongo.find(driveQuery, RecruitmentDrive.class).stream().collect(Collectors.toMap(RecruitmentDrive::getId, Function.identity()));
        Map<String, Club> clubs = clubNames(drives.values().stream().map(RecruitmentDrive::getClub).collect(Collectors.toSet()));
        Map<String, Map<String, Object>> applicants = refs.users(expired.stream().map(RecruitmentApplication::getApplicant).toList(), "name");
        Map<String, List<String>> presidents = new HashMap<>();
        Set<String> touched = new LinkedHashSet<>();
        for (RecruitmentApplication application : expired) {
            boolean claimed = mongo.updateFirst(
                    Query.query(Criteria.where("_id").is(new ObjectId(application.getId())).and("status").is(ApplicationStatus.OFFERED.name())),
                    new Update().set("status", ApplicationStatus.EXPIRED.name()).set("decidedAt", now).set("updatedAt", now),
                    RecruitmentApplication.class).getModifiedCount() > 0;
            if (!claimed) {
                continue;
            }
            RecruitmentDrive drive = drives.get(application.getDrive());
            Club club = drive == null ? null : clubs.get(drive.getClub());
            RecruitmentPosition position = drive == null ? null : Drives.positionOrNull(drive, application.getPosition());
            if (club == null || position == null) {
                continue;
            }
            audit.record(AuditAction.OFFER_EXPIRED, drive.getCreatedBy(), "RecruitmentApplication", application.getId(), Map.of("driveId", new ObjectId(drive.getId())));
            mailer.sendOfferExpired(drive, club, position, application.getApplicant());
            Map<String, Object> applicant = applicants.get(application.getApplicant());
            Object name = applicant == null ? null : applicant.get("name");
            notifications.notify(presidents.computeIfAbsent(drive.getClub(), clubId -> clubAccess.usersWithPermission(clubId, ClubPermission.MANAGE_RECRUITMENT)),
                    Notice.builder()
                            .type(NotificationType.RECRUITMENT_UPDATE)
                            .title("Offer expired: " + position.getTitle())
                            .message((name == null || String.valueOf(name).isEmpty() ? "A candidate" : String.valueOf(name))
                                    + " didn't answer in time. You can offer the seat to a reserve candidate.")
                            .link("/recruitment/" + drive.getId() + "?tab=selection&role=" + position.getId())
                            .build());
            touched.add(drive.getId());
        }
        touched.forEach(completion::completeIfDone);
        return expired.size();
    }
}
