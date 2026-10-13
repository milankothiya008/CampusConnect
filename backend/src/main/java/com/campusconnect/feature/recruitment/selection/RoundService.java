package com.campusconnect.feature.recruitment.selection;

import static com.campusconnect.feature.recruitment.drive.DrivePhases.ACTIVE_APPLICATION;
import static com.campusconnect.feature.recruitment.drive.DrivePhases.names;
import static com.campusconnect.feature.recruitment.drive.Drives.forRound;
import static com.campusconnect.feature.recruitment.drive.Drives.invalid;
import static com.campusconnect.feature.recruitment.drive.Drives.invalidState;
import static com.campusconnect.feature.recruitment.drive.Drives.roundField;

import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.InterviewSlot;
import com.campusconnect.domain.recruitment.PendingOutcome;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentRound;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.recruitment.RoundMode;
import com.campusconnect.domain.recruitment.RoundOutcome;
import com.campusconnect.domain.recruitment.RoundResult;
import com.campusconnect.domain.recruitment.RoundStatus;
import com.campusconnect.domain.recruitment.RoundTiming;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.domain.venue.VenueStatus;
import com.campusconnect.feature.recruitment.drive.DrivePhases;
import com.campusconnect.feature.recruitment.drive.Drives;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.SlotTime;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests.ScheduleBody;
import com.campusconnect.feature.recruitment.dto.SelectionViews.Candidate;
import com.campusconnect.feature.recruitment.dto.SelectionViews.Finalist;
import com.campusconnect.feature.recruitment.dto.SelectionViews.Offer;
import com.campusconnect.feature.recruitment.dto.SelectionViews.PositionSummary;
import com.campusconnect.feature.recruitment.dto.SelectionViews.RoundDetail;
import com.campusconnect.feature.recruitment.dto.SelectionViews.ScheduledSelection;
import com.campusconnect.feature.recruitment.dto.SelectionViews.Seats;
import com.campusconnect.feature.recruitment.dto.SelectionViews.Selection;
import com.campusconnect.feature.recruitment.dto.SelectionViews.Warning;
import com.campusconnect.feature.recruitment.mail.RecruitmentMailer;
import com.campusconnect.feature.recruitment.mail.RecruitmentMailer.Slot;
import com.campusconnect.feature.recruitment.support.IsoTimes;
import com.campusconnect.feature.recruitment.support.Js;
import com.campusconnect.feature.venue.VenueService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Selection rounds of each role of a published drive, run by the president after applications close
 * (services/RecruitmentRoundService.js): screening, online or offline interviews (one common time or one slot per
 * candidate), draft decisions, and published results e-mailed to every candidate. Offline rounds book their venue
 * for the whole round, checked and claimed under the venue's lock so no event or other round can take it meanwhile.
 */
@Service
public class RoundService {

    static final int MAX_ROUNDS = 8;
    static final String[] APPLICANT_FIELDS = {"name", "email", "departmentCode", "batchCode"};
    private static final Pattern HTTPS_URL = Pattern.compile("^https://[^\\s/$.?#].[^\\s]*$", Pattern.CASE_INSENSITIVE);
    private static final List<ApplicationStatus> OFFER_STATES = List.of(ApplicationStatus.OFFERED, ApplicationStatus.ACCEPTED, ApplicationStatus.DECLINED,
            ApplicationStatus.EXPIRED, ApplicationStatus.RESERVE);

    private final MongoOperations mongo;
    private final Drives drives;
    private final VenueService venueService;
    private final AuditService audit;
    private final RecruitmentMailer mailer;
    private final Refs refs;

    public RoundService(MongoOperations mongo, Drives drives, VenueService venueService, AuditService audit, RecruitmentMailer mailer, Refs refs) {
        this.mongo = mongo;
        this.drives = drives;
        this.venueService = venueService;
        this.audit = audit;
        this.mailer = mailer;
        this.refs = refs;
    }

    static ObjectId oid(String id) {
        return new ObjectId(id);
    }

    static RecruitmentRound lastRound(RecruitmentPosition position) {
        List<RecruitmentRound> rounds = position.getRounds();
        return rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
    }

    /** Candidates still in play for a role, in application order. */
    public List<RecruitmentApplication> activeCandidates(String driveId, String positionId) {
        Query query = Query.query(Criteria.where("drive").is(oid(driveId)).and("position").is(oid(positionId)).and("status").in(names(ACTIVE_APPLICATION)))
                .with(Sort.by(Sort.Direction.ASC, "createdAt", "_id"));
        query.fields().exclude("answers");
        return mongo.find(query, RecruitmentApplication.class);
    }

    void audit(AuditAction action, AuthUser actor, RecruitmentDrive drive, RecruitmentPosition position, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", oid(drive.getClub()));
        metadata.put("role", position.getRole());
        metadata.put("positionTitle", position.getTitle());
        metadata.putAll(extra);
        audit.record(action, actor.id(), "RecruitmentDrive", drive.getId(), metadata);
    }

    /** Only the latest round, before its results are published and the role's final selection, can change. */
    static RecruitmentRound currentRound(RecruitmentPosition position, String roundId) {
        RecruitmentRound round = position.getRounds().stream().filter(item -> item.getId().equals(roundId)).findFirst().orElse(null);
        if (round == null) {
            throw AppException.notFound("Round not found");
        }
        if (!lastRound(position).getId().equals(round.getId())) {
            throw invalidState("Only the latest round can change");
        }
        if (round.getStatus() == RoundStatus.RESULTS_PUBLISHED) {
            throw invalidState("This round's results are published, so it can't change any more");
        }
        if (position.getFinalizedAt() != null) {
            throw invalidState("The final selection for this role is done");
        }
        return round;
    }

    /** Matches the drive while this round of the role exists and its results are not published. */
    static Criteria roundOpen(String positionId, String roundId) {
        return Drives.positionWhere(positionId, Criteria.where("rounds").elemMatch(Criteria.where("_id").is(oid(roundId)).and("status").ne(RoundStatus.RESULTS_PUBLISHED.name())));
    }

    // ---------------------------------------------------------------- Views

    /** The Selection tab for one role: its rounds with candidates, the final selection and the offers. */
    public Selection getRounds(AuthUser actor, String driveId, String positionId) {
        RecruitmentDrive drive = drives.load(driveId);
        drives.assertStaff(actor, drive);
        RecruitmentPosition position = Drives.position(drive, positionId);
        Map<String, Map<String, Object>> venues = drives.venues(List.of(drive), "name", "location", "capacity");
        Query query = Query.query(Criteria.where("drive").is(oid(drive.getId())).and("position").is(oid(position.getId())).and("status").ne(ApplicationStatus.WITHDRAWN.name()))
                .with(Sort.by(Sort.Direction.ASC, "createdAt", "_id"));
        query.fields().exclude("answers").exclude("remindersSent");
        List<RecruitmentApplication> applications = mongo.find(query, RecruitmentApplication.class);
        Map<String, Map<String, Object>> applicants = refs.users(applications.stream().map(RecruitmentApplication::getApplicant).toList(), APPLICANT_FIELDS);
        return selection(drive, position, applications, applicants, venues, Instant.now());
    }

    static Selection selection(RecruitmentDrive drive, RecruitmentPosition position, List<RecruitmentApplication> applications, Map<String, Map<String, Object>> applicants,
            Map<String, Map<String, Object>> venues, Instant now) {
        RecruitmentRound last = lastRound(position);
        List<RoundDetail> rounds = new ArrayList<>();
        for (RecruitmentRound round : position.getRounds()) {
            boolean isCurrent = last != null && last.getId().equals(round.getId()) && round.getStatus() != RoundStatus.RESULTS_PUBLISHED && position.getFinalizedAt() == null;
            List<Candidate> candidates = new ArrayList<>();
            for (RecruitmentApplication application : applications) {
                RoundResult result = resultOf(application, round);
                if (result == null && !(isCurrent && ACTIVE_APPLICATION.contains(application.getStatus()))) {
                    continue;
                }
                PendingOutcome pending = application.getPendingOutcome() != null && round.getId().equals(application.getPendingOutcome().getRound())
                        ? application.getPendingOutcome() : null;
                InterviewSlot slot = slotOf(application, round);
                candidates.add(new Candidate(
                        application.getId(),
                        applicants.get(application.getApplicant()),
                        result != null ? result.getOutcome() : pending != null ? pending.getOutcome() : null,
                        result != null ? result.getNote() : pending != null && Js.truthy(pending.getNote()) ? pending.getNote() : null,
                        result != null,
                        slot == null ? null : new SlotTime(slot.getStartAt(), slot.getEndAt())));
            }
            rounds.add(new RoundDetail(round.getId(), round.getName(), round.getMode(), round.getTiming(), round.getStartAt(), round.getEndAt(), round.getSlotMinutes(),
                    round.getVenue() == null ? null : venues.get(round.getVenue()), round.getMeetingLink(), round.getInstructions(), round.getStatus(),
                    round.getScheduledAt(), round.getResultsPublishedAt(), round.getCreatedAt(), round.getUpdatedAt(), isCurrent, candidates));
        }
        List<RecruitmentApplication> active = applications.stream().filter(application -> ACTIVE_APPLICATION.contains(application.getStatus())).toList();
        String phase = DrivePhases.phaseOf(drive, now);
        boolean selectionOpen = drive.getStatus() == RecruitmentStatus.PUBLISHED && (DrivePhases.CLOSED.equals(phase) || DrivePhases.ROUNDS.equals(phase))
                && position.getFinalizedAt() == null;
        boolean roundOpen = last != null && last.getStatus() != RoundStatus.RESULTS_PUBLISHED;
        List<RecruitmentApplication> offers = position.getFinalizedAt() == null ? List.of()
                : applications.stream().filter(application -> OFFER_STATES.contains(application.getStatus())).toList();
        int accepted = (int) offers.stream().filter(application -> application.getStatus() == ApplicationStatus.ACCEPTED).count();
        int pendingOffers = (int) offers.stream().filter(application -> application.getStatus() == ApplicationStatus.OFFERED).count();
        Integer openings = position.getOpenings();
        Integer openSeats = openings != null && openings != 0 ? Math.max(0, openings - accepted - pendingOffers) : null;
        boolean ready = selectionOpen && !roundOpen;
        return new Selection(
                phase,
                new PositionSummary(position.getId(), position.getTitle(), position.getRole(), openings, position.getFinalizedAt(), position.getOfferDays(),
                        DrivePhases.stageOf(drive, position, now)),
                rounds,
                active.size(),
                ready && !active.isEmpty() && position.getRounds().size() < MAX_ROUNDS,
                ready,
                ready ? active.stream().map(application -> new Finalist(application.getId(), applicants.get(application.getApplicant()))).toList() : List.of(),
                offers.stream().map(application -> new Offer(application.getId(), applicants.get(application.getApplicant()), application.getStatus(),
                        application.getOfferedAt(), application.getOfferExpiresAt(), application.getRespondedAt())).toList(),
                new Seats(openings, accepted, pendingOffers, openSeats),
                position.getFinalizedAt() != null && drive.getStatus() == RecruitmentStatus.PUBLISHED && (openSeats == null || openSeats > 0)
                        && offers.stream().anyMatch(application -> application.getStatus() == ApplicationStatus.RESERVE));
    }

    static RoundResult resultOf(RecruitmentApplication application, RecruitmentRound round) {
        return application.getRoundResults().stream().filter(result -> round.getId().equals(result.getRound())).findFirst().orElse(null);
    }

    static InterviewSlot slotOf(RecruitmentApplication application, RecruitmentRound round) {
        return application.getSlots().stream().filter(slot -> round.getId().equals(slot.getRound())).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- Rounds

    public Selection createRound(AuthUser actor, String driveId, String positionId, String name, String mode) {
        Drives.Running running = drives.running(actor, driveId, positionId);
        RecruitmentDrive drive = running.drive();
        RecruitmentPosition position = running.position();
        if (position.getFinalizedAt() != null) {
            throw invalidState("The final selection for this role is done");
        }
        RecruitmentRound last = lastRound(position);
        if (last != null && last.getStatus() != RoundStatus.RESULTS_PUBLISHED) {
            throw invalidState("Publish the results of \"" + last.getName() + "\" before adding the next round");
        }
        if (position.getRounds().size() >= MAX_ROUNDS) {
            throw invalidState("A role can have up to " + MAX_ROUNDS + " rounds");
        }
        RoundMode roundMode = Arrays.stream(RoundMode.values()).filter(value -> value.name().equals(mode)).findFirst()
                .orElseThrow(() -> invalid("Choose screening, online interview or offline interview"));
        String title = Js.truthy(name) ? Js.clean(name, 80) : "";
        if (title.isEmpty()) {
            throw invalid("Name the round, e.g. \"Technical interview\"");
        }
        if (!mongo.exists(Query.query(Criteria.where("drive").is(oid(drive.getId())).and("position").is(oid(position.getId())).and("status").in(names(ACTIVE_APPLICATION))),
                RecruitmentApplication.class)) {
            throw invalidState("There are no candidates left for another round");
        }
        RecruitmentRound round = new RecruitmentRound();
        round.setName(title);
        round.setMode(roundMode);
        round.setStatus(RoundStatus.DRAFT);
        // A double click adds one round: the role must still have exactly the rounds seen above.
        Criteria unchanged = Drives.positionWhere(position.getId(), Criteria.where("finalizedAt").is(null).and("rounds").size(position.getRounds().size()));
        if (!drives.update(drive.getId(), unchanged, Drives.forPosition(new Update().push(Drives.positionField("rounds"), drives.toDocument(round)), position.getId()))) {
            RecruitmentRound newest = lastRound(Drives.position(drives.load(drive.getId()), position.getId()));
            throw invalidState(newest == null ? "The final selection for this role is done" : "Publish the results of \"" + newest.getName() + "\" before adding the next round");
        }
        audit(AuditAction.ROUND_CREATED, actor, drive, position, Map.of("round", title, "mode", roundMode.name()));
        return getRounds(actor, drive.getId(), position.getId());
    }

    private static Instant readTime(String value, String label) {
        Instant time = Js.truthy(value) ? IsoTimes.parse(value) : null;
        if (time == null) {
            throw invalid("Set the " + label);
        }
        return time;
    }

    /** A candidate's interview times in the drive's other roles (they can apply to several). */
    private Map<String, List<SlotPlanner.Busy>> otherSlots(RecruitmentDrive drive, RecruitmentPosition position, List<RecruitmentApplication> candidates) {
        Query query = Query.query(Criteria.where("drive").is(oid(drive.getId()))
                .and("applicant").in(candidates.stream().map(candidate -> oid(candidate.getApplicant())).toList())
                .and("position").ne(oid(position.getId()))
                .and("status").in(names(ACTIVE_APPLICATION)));
        query.fields().include("applicant").include("slots").include("position");
        Map<String, List<SlotPlanner.Busy>> byApplicant = new HashMap<>();
        for (RecruitmentApplication other : mongo.find(query, RecruitmentApplication.class)) {
            String role = Drives.positionTitle(drive, other.getPosition());
            List<SlotPlanner.Busy> busy = byApplicant.computeIfAbsent(other.getApplicant(), unused -> new ArrayList<>());
            other.getSlots().forEach(slot -> busy.add(new SlotPlanner.Busy(slot.getStartAt(), slot.getEndAt(), role)));
        }
        return byApplicant;
    }

    /** The candidate's slots with this round's slot replaced. */
    private static List<Document> slotsWith(RecruitmentApplication application, String roundId, Slot slot) {
        List<Document> slots = new ArrayList<>();
        for (InterviewSlot existing : application.getSlots()) {
            if (!roundId.equals(existing.getRound())) {
                slots.add(new Document("round", oid(existing.getRound())).append("startAt", existing.getStartAt()).append("endAt", existing.getEndAt()));
            }
        }
        slots.add(new Document("round", oid(roundId)).append("startAt", slot.startAt()).append("endAt", slot.endAt()));
        return slots;
    }

    /**
     * Sets (or changes) an interview round's time and place and invites every candidate. COMMON: one start and end
     * for everyone; SLOTS: back-to-back slots of {@code slotMinutes}. Candidates also interviewing for another role
     * are placed in slots that don't clash with it; anyone who still clashes is listed in {@code warnings}.
     */
    public ScheduledSelection scheduleRound(AuthUser actor, String driveId, String positionId, String roundId, ScheduleBody body) {
        Drives.Running running = drives.running(actor, driveId, positionId);
        RecruitmentDrive drive = running.drive();
        RecruitmentPosition position = running.position();
        Club club = running.club();
        RecruitmentRound round = currentRound(position, roundId);
        if (round.getMode() == RoundMode.SCREENING) {
            throw invalidState("Screening rounds have no interview to schedule");
        }
        RoundTiming timing = Arrays.stream(RoundTiming.values()).filter(value -> value.name().equals(body.timing())).findFirst()
                .orElseThrow(() -> invalid("Choose one common time or individual slots"));
        Instant startAt = readTime(body.startAt(), "start time");
        if (!startAt.isAfter(Instant.now())) {
            throw invalid("The round must start in the future");
        }
        List<RecruitmentApplication> candidates = activeCandidates(drive.getId(), position.getId());
        if (candidates.isEmpty()) {
            throw invalidState("There are no candidates in this round");
        }
        Instant endAt;
        Integer slotMinutes = null;
        if (timing == RoundTiming.COMMON) {
            endAt = readTime(body.endAt(), "end time");
            if (!endAt.isAfter(startAt)) {
                throw invalid("The round must end after it starts");
            }
        } else {
            double minutes = body.slotMinutes() == null ? Double.NaN : Js.number(body.slotMinutes());
            if (!Js.isInteger(minutes) || minutes < 5 || minutes > 240) {
                throw invalid("Each slot must be 5 to 240 minutes long");
            }
            slotMinutes = (int) minutes;
            endAt = startAt.plus(Duration.ofMinutes((long) candidates.size() * slotMinutes));
        }
        if (Duration.between(startAt, endAt).toMillis() > 3 * 86_400_000L) {
            throw invalid("A round can span up to 3 days. Split long interview days into separate rounds.");
        }

        Venue venue = null;
        String meetingLink = null;
        if (round.getMode() == RoundMode.OFFLINE) {
            venue = body.venue() != null && ObjectId.isValid(body.venue()) ? mongo.findById(body.venue(), Venue.class) : null;
            if (venue == null || venue.getStatus() != VenueStatus.ACTIVE) {
                throw invalid("Choose an available venue");
            }
            VenueService.assertVenueFitsAudience(venue, VenueService.audienceOf(club));
        } else {
            meetingLink = Js.trim(body.meetingLink() == null ? "" : body.meetingLink());
            if (!HTTPS_URL.matcher(meetingLink).matches()) {
                throw invalid("Add the meeting link (it must start with https://)");
            }
        }

        SlotPlanner.Plan plan = SlotPlanner.plan(candidates, timing, startAt, endAt, slotMinutes, otherSlots(drive, position, candidates));
        boolean wasScheduled = round.getStatus() == RoundStatus.SCHEDULED;
        String instructions = Js.truthy(body.instructions()) ? Js.clean(body.instructions(), 1000) : "";
        Instant now = DrivePhases.now();
        round.setTiming(timing);
        round.setStartAt(startAt);
        round.setEndAt(endAt);
        round.setSlotMinutes(slotMinutes);
        round.setVenue(venue == null ? null : venue.getId());
        round.setMeetingLink(meetingLink == null || meetingLink.isEmpty() ? null : meetingLink);
        round.setInstructions(instructions);
        round.setStatus(RoundStatus.SCHEDULED);
        round.setScheduledAt(now);
        Update update = forRound(new Update()
                .set(roundField("timing"), timing.name())
                .set(roundField("startAt"), startAt)
                .set(roundField("endAt"), endAt)
                .set(roundField("slotMinutes"), slotMinutes)
                .set(roundField("venue"), venue == null ? null : oid(venue.getId()))
                .set(roundField("meetingLink"), round.getMeetingLink())
                .set(roundField("instructions"), instructions)
                .set(roundField("status"), RoundStatus.SCHEDULED.name())
                .set(roundField("scheduledAt"), now)
                .set(roundField("updatedAt"), now), position.getId(), round.getId());
        Runnable save = () -> {
            if (round.getVenue() != null) {
                venueService.assertVenueAvailable(round.getVenue(), startAt, endAt, null, round.getId());
            }
            if (!drives.update(drive.getId(), roundOpen(position.getId(), round.getId()), update)) {
                throw invalidState("This round's results are published, so it can't change any more");
            }
        };
        if (round.getVenue() != null) {
            venueService.withVenueLock(round.getVenue(), save);
        } else {
            save.run();
        }

        // Each candidate's own time. Reminders start afresh for the new times.
        BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, RecruitmentApplication.class);
        Map<String, Slot> invitations = new LinkedHashMap<>();
        for (SlotPlanner.Assignment assignment : plan.assignments()) {
            RecruitmentApplication candidate = assignment.candidate();
            Update slotUpdate = new Update().set("slots", slotsWith(candidate, round.getId(), assignment.slot()))
                    .pull("remindersSent", new Document("round", oid(round.getId())))
                    .set("updatedAt", now);
            if (candidate.getStatus() == ApplicationStatus.APPLIED) {
                slotUpdate.set("status", ApplicationStatus.IN_ROUNDS.name());
            }
            bulk.updateOne(Query.query(Criteria.where("_id").is(oid(candidate.getId())).and("status").in(names(ACTIVE_APPLICATION))), slotUpdate);
            invitations.put(candidate.getApplicant(), assignment.slot());
        }
        bulk.execute();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("round", round.getName());
        details.put("timing", timing.name());
        details.put("startAt", startAt);
        details.put("endAt", endAt);
        details.put("rescheduled", wasScheduled);
        audit(AuditAction.ROUND_SCHEDULED, actor, drive, position, details);
        mailer.sendInterviewInvites(drive, club, position, round, invitations, venue, wasScheduled);
        return new ScheduledSelection(getRounds(actor, drive.getId(), position.getId()), plan.warnings());
    }

    /** Moves one candidate's slot (individual-slot rounds) and tells them. */
    public ScheduledSelection updateSlot(AuthUser actor, String driveId, String positionId, String roundId, String applicationId, String value) {
        Drives.Running running = drives.running(actor, driveId, positionId);
        RecruitmentDrive drive = running.drive();
        RecruitmentPosition position = running.position();
        RecruitmentRound round = currentRound(position, roundId);
        if (round.getStatus() != RoundStatus.SCHEDULED || round.getTiming() != RoundTiming.SLOTS) {
            throw invalidState("Only scheduled rounds with individual slots have slots to move");
        }
        Instant startAt = readTime(value, "new time");
        if (!startAt.isAfter(Instant.now())) {
            throw invalid("The new time must be in the future");
        }
        Instant endAt = startAt.plus(Duration.ofMinutes(round.getSlotMinutes() == null ? 0 : round.getSlotMinutes()));
        Query candidateQuery = Query.query(Criteria.where("_id").is(oid(applicationId)).and("drive").is(oid(drive.getId())).and("position").is(oid(position.getId()))
                .and("status").in(names(ACTIVE_APPLICATION)));
        candidateQuery.fields().exclude("answers");
        RecruitmentApplication application = mongo.findOne(candidateQuery, RecruitmentApplication.class);
        if (application == null) {
            throw AppException.notFound("Candidate not found");
        }

        // The round's booking grows to cover a slot moved outside it.
        Instant roundStart = startAt.isBefore(round.getStartAt()) ? startAt : round.getStartAt();
        Instant roundEnd = endAt.isAfter(round.getEndAt()) ? endAt : round.getEndAt();
        Instant now = DrivePhases.now();
        Runnable save = () -> {
            if (round.getVenue() != null && (roundStart.isBefore(round.getStartAt()) || roundEnd.isAfter(round.getEndAt()))) {
                venueService.assertVenueAvailable(round.getVenue(), roundStart, roundEnd, null, round.getId());
            }
            drives.update(drive.getId(), roundOpen(position.getId(), round.getId()), forRound(new Update()
                    .set(roundField("startAt"), roundStart)
                    .set(roundField("endAt"), roundEnd)
                    .set(roundField("updatedAt"), now), position.getId(), round.getId()));
        };
        if (round.getVenue() != null) {
            venueService.withVenueLock(round.getVenue(), save);
        } else {
            save.run();
        }

        Slot slot = new Slot(startAt, endAt);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(application.getId()))),
                new Update().set("slots", slotsWith(application, round.getId(), slot)).pull("remindersSent", new Document("round", oid(round.getId()))).set("updatedAt", now),
                RecruitmentApplication.class);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("round", round.getName());
        details.put("applicationId", applicationId);
        details.put("startAt", startAt);
        audit(AuditAction.INTERVIEW_SLOT_CHANGED, actor, drive, position, details);

        SlotPlanner.Busy clash = otherSlots(drive, position, List.of(application)).getOrDefault(application.getApplicant(), List.of()).stream()
                .filter(other -> SlotPlanner.overlaps(startAt, endAt, other.startAt(), other.endAt())).findFirst().orElse(null);
        Venue venue = round.getVenue() == null ? null : mongo.findById(round.getVenue(), Venue.class);
        mailer.sendInterviewInvites(drive, running.club(), position, round, Map.of(application.getApplicant(), slot), venue, true);
        List<Warning> warnings = clash == null ? List.of()
                : List.of(new Warning(application.getId(), clash.role(), CampusTime.formatSchedule(clash.startAt(), clash.endAt())));
        return new ScheduledSelection(getRounds(actor, drive.getId(), position.getId()), warnings);
    }

    /** Draft decisions for the current round; nothing is shown to candidates until the results are published. */
    public Selection setOutcomes(AuthUser actor, String driveId, String positionId, String roundId, List<Map<String, Object>> decisions) {
        Drives.Running running = drives.running(actor, driveId, positionId);
        RecruitmentDrive drive = running.drive();
        RecruitmentPosition position = running.position();
        RecruitmentRound round = currentRound(position, roundId);
        if (round.getMode() != RoundMode.SCREENING && round.getStatus() != RoundStatus.SCHEDULED) {
            throw invalidState("Schedule the interview before recording results");
        }
        List<Map<String, Object>> list = decisions == null ? List.of() : decisions.subList(0, Math.min(1000, decisions.size()));
        BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, RecruitmentApplication.class);
        int writes = 0;
        for (Map<String, Object> decision : list) {
            Object rawOutcome = decision == null ? null : decision.get("outcome");
            String outcome = Js.truthy(rawOutcome) ? Js.string(rawOutcome) : null;
            if (outcome != null && Arrays.stream(RoundOutcome.values()).noneMatch(value -> value.name().equals(outcome))) {
                throw invalid("Mark each candidate as qualified or eliminated");
            }
            Object applicationId = decision == null ? null : decision.get("applicationId");
            if (applicationId == null) {
                continue;
            }
            if (!ObjectId.isValid(Js.string(applicationId))) {
                throw AppException.badRequest("Invalid _id: " + Js.string(applicationId));
            }
            Object note = decision.get("note");
            String cleaned = Js.truthy(note) ? Js.clean(note, 500) : "";
            Document pending = new Document("round", oid(round.getId())).append("outcome", outcome).append("note", cleaned.isEmpty() ? null : cleaned);
            bulk.updateOne(Query.query(Criteria.where("_id").is(oid(Js.string(applicationId))).and("drive").is(oid(drive.getId())).and("position").is(oid(position.getId()))
                    .and("status").in(names(ACTIVE_APPLICATION))), new Update().set("pendingOutcome", pending).set("updatedAt", Instant.now()));
            writes++;
        }
        if (writes > 0) {
            bulk.execute();
        }
        return getRounds(actor, drive.getId(), position.getId());
    }

    /** Publishes the current round: qualified candidates are congratulated, eliminated ones thanked. */
    public Selection publishRoundResults(AuthUser actor, String driveId, String positionId, String roundId) {
        Drives.Running running = drives.running(actor, driveId, positionId);
        RecruitmentDrive drive = running.drive();
        RecruitmentPosition position = running.position();
        RecruitmentRound round = currentRound(position, roundId);
        if (round.getMode() != RoundMode.SCREENING && round.getStatus() != RoundStatus.SCHEDULED) {
            throw invalidState("Schedule the interview before publishing results");
        }
        List<RecruitmentApplication> candidates = activeCandidates(drive.getId(), position.getId());
        long undecided = candidates.stream().filter(application -> application.getPendingOutcome() == null || !round.getId().equals(application.getPendingOutcome().getRound())
                || application.getPendingOutcome().getOutcome() == null).count();
        if (undecided > 0) {
            throw invalidState("Decide every candidate first — " + undecided + " still " + (undecided == 1 ? "has" : "have") + " no result");
        }
        Instant now = DrivePhases.now();
        List<String> qualified = new ArrayList<>();
        List<String> eliminated = new ArrayList<>();
        for (RecruitmentApplication application : candidates) {
            PendingOutcome pending = application.getPendingOutcome();
            boolean passed = pending.getOutcome() == RoundOutcome.QUALIFIED;
            RoundResult result = new RoundResult();
            result.setRound(round.getId());
            result.setOutcome(pending.getOutcome());
            result.setNote(pending.getNote());
            result.setPublishedAt(now);
            Update update = new Update().push("roundResults", drives.toDocument(result))
                    .set("status", (passed ? ApplicationStatus.IN_ROUNDS : ApplicationStatus.ELIMINATED).name())
                    .set("pendingOutcome", new Document("round", null).append("outcome", null).append("note", null))
                    .set("updatedAt", now);
            if (!passed) {
                update.set("decidedAt", now);
            }
            // Claimed per candidate: a second click finds the decision already published and adds nothing.
            boolean published = mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(application.getId())).and("status").in(names(ACTIVE_APPLICATION))
                    .and("pendingOutcome.round").is(oid(round.getId())).and("pendingOutcome.outcome").is(pending.getOutcome().name())), update,
                    RecruitmentApplication.class).getModifiedCount() > 0;
            if (published) {
                (passed ? qualified : eliminated).add(application.getApplicant());
            }
        }
        drives.update(drive.getId(), roundOpen(position.getId(), round.getId()), forRound(new Update()
                .set(roundField("status"), RoundStatus.RESULTS_PUBLISHED.name())
                .set(roundField("resultsPublishedAt"), now)
                .set(roundField("updatedAt"), now), position.getId(), round.getId()));
        audit(AuditAction.ROUND_RESULTS_PUBLISHED, actor, drive, position, Map.of("round", round.getName(), "qualified", qualified.size(), "eliminated", eliminated.size()));
        mailer.sendRoundResults(drive, running.club(), position, round, qualified, eliminated);
        return getRounds(actor, drive.getId(), position.getId());
    }
}
