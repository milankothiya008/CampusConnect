package com.campusconnect.feature.recruitment.drive;

import static com.campusconnect.feature.recruitment.drive.DrivePhases.ACTIVE;
import static com.campusconnect.feature.recruitment.drive.DrivePhases.EDITABLE;
import static com.campusconnect.feature.recruitment.drive.DrivePhases.OPEN_APPLICATION;
import static com.campusconnect.feature.recruitment.drive.DrivePhases.names;
import static com.campusconnect.feature.recruitment.drive.Drives.invalid;
import static com.campusconnect.feature.recruitment.drive.Drives.invalidState;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentEligibility;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentRound;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.feature.recruitment.dto.DriveViews.ClubDriveItem;
import com.campusconnect.feature.recruitment.dto.DriveViews.ClubDrives;
import com.campusconnect.feature.recruitment.dto.DriveViews.ClubSummary;
import com.campusconnect.feature.recruitment.dto.DriveViews.DriveCounts;
import com.campusconnect.feature.recruitment.dto.DriveViews.DriveView;
import com.campusconnect.feature.recruitment.dto.DriveViews.OpenDrive;
import com.campusconnect.feature.recruitment.dto.DriveViews.OpenDriveItem;
import com.campusconnect.feature.recruitment.dto.DriveViews.PositionView;
import com.campusconnect.feature.recruitment.dto.DriveViews.PublicRound;
import com.campusconnect.feature.recruitment.dto.DriveViews.ReviewItem;
import com.campusconnect.feature.recruitment.dto.DriveViews.RoundSummary;
import com.campusconnect.feature.recruitment.dto.DriveViews.StaffFields;
import com.campusconnect.feature.recruitment.dto.DriveViews.StaffRound;
import com.campusconnect.feature.recruitment.dto.DriveViews.Viewer;
import com.campusconnect.feature.recruitment.dto.DriveViews.ViewerApplication;
import com.campusconnect.feature.recruitment.mail.RecruitmentMailer;
import com.campusconnect.feature.recruitment.support.IsoTimes;
import com.campusconnect.feature.recruitment.support.Js;
import com.campusconnect.feature.recruitment.support.RecruitmentLocks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recruitment drives (services/RecruitmentService.js): the only way to join a club. The president builds one drive
 * with a position per club role being recruited; every role has its own page-wise application form, its own
 * selection rounds and its own results. The faculty mentor approves the drive, the president publishes it and
 * eligible students apply — to one or several roles, one application each. Status changes are conditional updates,
 * so two clicks (or two people) can never move a drive twice.
 */
@Service
public class DriveService {

    private static final String TARGET = "RecruitmentDrive";
    private static final List<RecruitmentStatus> FINISHED = List.of(RecruitmentStatus.COMPLETED, RecruitmentStatus.CANCELLED, RecruitmentStatus.REJECTED);
    private static final String DRIVE_NOT_FOUND = "Recruitment drive not found";

    private final MongoOperations mongo;
    private final Drives drives;
    private final ClubAccess clubAccess;
    private final PausedClubs pausedClubs;
    private final Refs refs;
    private final AuditService audit;
    private final NotificationService notifications;
    private final RecruitmentMailer mailer;
    private final JsonMapper json;
    private final RecruitmentLocks locks;

    public DriveService(MongoOperations mongo, Drives drives, ClubAccess clubAccess, PausedClubs pausedClubs, Refs refs, AuditService audit,
            NotificationService notifications, RecruitmentMailer mailer, JsonMapper json, RecruitmentLocks locks) {
        this.mongo = mongo;
        this.drives = drives;
        this.clubAccess = clubAccess;
        this.pausedClubs = pausedClubs;
        this.refs = refs;
        this.audit = audit;
        this.notifications = notifications;
        this.mailer = mailer;
        this.json = json;
        this.locks = locks;
    }

    // ---------------------------------------------------------------- Views

    public static ClubSummary clubSummary(Club club) {
        return new ClubSummary(club.getId(), club.getName(), club.getLogo(), club.getCoverImage(), club.getCategory(), club.isAllDepartments(), club.getDepartmentCodes());
    }

    private static PublicRound publicRound(RecruitmentRound round) {
        return new PublicRound(round.getId(), round.getName(), round.getMode(), round.getTiming(), round.getStatus(), round.getResultsPublishedAt());
    }

    private static StaffRound staffRound(RecruitmentRound round, Map<String, Map<String, Object>> venues) {
        return new StaffRound(round.getId(), round.getName(), round.getMode(), round.getTiming(), round.getStatus(), round.getResultsPublishedAt(),
                round.getStartAt(), round.getEndAt(), round.getSlotMinutes(), round.getVenue() == null ? null : venues.get(round.getVenue()), round.getMeetingLink(),
                round.getInstructions(), round.getScheduledAt());
    }

    private static PositionView positionView(RecruitmentDrive drive, RecruitmentPosition position, boolean staff, DriveCounts counts,
            Map<String, Map<String, Object>> venues, Instant now) {
        List<RoundSummary> rounds = position.getRounds().stream().map(round -> staff ? (RoundSummary) staffRound(round, venues) : publicRound(round)).toList();
        return new PositionView(position.getId(), position.getRole(), position.getTitle(), position.getOpenings(), position.getDescription(), position.getForm(),
                DrivePhases.questionsOf(position).size(), DrivePhases.stageOf(drive, position, now), position.getFinalizedAt(), rounds,
                staff ? position.getOfferDays() : null,
                staff ? counts.byPosition().getOrDefault(position.getId(), Drives.emptyPositionCounts()) : null);
    }

    private static boolean isPublic(RecruitmentDrive drive) {
        return drive.getStatus() == RecruitmentStatus.PUBLISHED || drive.getStatus() == RecruitmentStatus.COMPLETED
                || (drive.getStatus() == RecruitmentStatus.CANCELLED && drive.getPublishedAt() != null);
    }

    /** The drive page: what a visitor, an applicant, the president or the mentor may see. */
    public DriveView getDrive(AuthUser actor, String driveId) {
        RecruitmentDrive drive = drives.load(driveId);
        Club club = clubAccess.loadClub(drive.getClub());
        Drives.Context context = actor != null ? drives.context(actor, club) : null;
        boolean canManage = context != null && context.canManage();
        boolean isMentor = context != null && context.isMentor();
        boolean staff = canManage || isMentor;
        if (!isPublic(drive) && !staff) {
            throw AppException.notFound(DRIVE_NOT_FOUND);
        }
        Instant now = DrivePhases.now();
        DriveCounts counts = staff ? drives.counts(drive.getId()) : null;
        List<RecruitmentApplication> mine = List.of();
        if (actor != null) {
            Query query = Query.query(Criteria.where("drive").is(new ObjectId(drive.getId())).and("applicant").is(new ObjectId(actor.id()))
                    .and("status").ne(ApplicationStatus.WITHDRAWN.name()));
            query.fields().include("position").include("status").include("createdAt").include("offerExpiresAt");
            mine = mongo.find(query, RecruitmentApplication.class);
        }
        String problem = ApplyRules.problem(actor, drive, club, context != null && context.club().isMember(), now);
        boolean joined = mine.stream().anyMatch(application -> application.getStatus() == ApplicationStatus.ACCEPTED);
        Map<String, Map<String, Object>> venues = staff ? drives.venues(List.of(drive), "name", "location") : Map.of();

        List<PositionView> positions = drive.getPositions().stream().map(position -> positionView(drive, position, staff, counts, venues, now)).toList();
        Viewer viewer = new Viewer(
                canManage,
                isMentor,
                isMentor && drive.getStatus() == RecruitmentStatus.PENDING_APPROVAL,
                canManage && EDITABLE.contains(drive.getStatus()),
                problem == null && !joined,
                joined ? "You've joined the club through this drive" : problem,
                mine.stream().map(application -> new ViewerApplication(application.getId(), application.getPosition(), application.getStatus(), application.getCreatedAt(),
                        application.getOfferExpiresAt())).toList());
        return new DriveView(drive.getId(), clubSummary(club), drive.getTitle(), drive.getDescription(), drive.getEligibility(), drive.getApplicationStart(),
                drive.getApplicationEnd(), drive.getClosedAt(), drive.getStatus(), DrivePhases.phaseOf(drive, now), drive.getPublishedAt(), drive.getCompletedAt(),
                drive.getCancelledAt(), drive.getCancellationReason(), drive.getCreatedAt(), positions,
                staff ? new StaffFields(counts, drive.getSubmittedAt(), drive.getReviewComment(), drive.getReviewedAt()) : null,
                viewer);
    }

    private static int roundCount(RecruitmentDrive drive) {
        return drive.getPositions().stream().mapToInt(position -> position.getRounds().size()).sum();
    }

    private static List<String> titles(RecruitmentDrive drive) {
        return drive.getPositions().stream().map(RecruitmentPosition::getTitle).toList();
    }

    /** Drives of one club: the public see published ones; the president and mentor see all of them. */
    public ClubDrives listClubDrives(AuthUser actor, String clubId) {
        Club club = clubAccess.loadClub(clubId);
        Drives.Context context = actor != null ? drives.context(actor, club) : null;
        boolean staff = context != null && context.isStaff();
        Criteria filter = Criteria.where("club").is(new ObjectId(club.getId()));
        if (!staff) {
            filter = filter.orOperator(
                    Criteria.where("status").in(names(List.of(RecruitmentStatus.PUBLISHED, RecruitmentStatus.COMPLETED))),
                    Criteria.where("status").is(RecruitmentStatus.CANCELLED.name()).and("publishedAt").ne(null));
        }
        Query query = Query.query(filter).with(Sort.by(Sort.Direction.DESC, "createdAt", "_id")).limit(30);
        query.fields().exclude("positions.form").exclude("description");
        List<RecruitmentDrive> list = mongo.find(query, RecruitmentDrive.class);
        Map<String, DriveCounts> counts = staff ? drives.counts(list.stream().map(RecruitmentDrive::getId).toList()) : Map.of();
        Instant now = DrivePhases.now();
        boolean canCreate = context != null && context.canManage() && club.getStatus() == ClubStatus.ACTIVE
                && list.stream().noneMatch(drive -> ACTIVE.contains(drive.getStatus()));
        List<ClubDriveItem> items = list.stream().map(drive -> new ClubDriveItem(drive.getId(), drive.getTitle(), drive.getStatus(), DrivePhases.phaseOf(drive, now),
                titles(drive), drive.getApplicationStart(), drive.getApplicationEnd(), drive.getClosedAt(), roundCount(drive), drive.getCompletedAt(),
                staff ? counts.get(drive.getId()) : null)).toList();
        return new ClubDrives(canCreate, items);
    }

    private static Query openDrivesQuery(Criteria clubCondition, Instant now) {
        Criteria criteria = Criteria.where("status").is(RecruitmentStatus.PUBLISHED.name()).and("closedAt").is(null).and("applicationEnd").gt(now);
        return Query.query(clubCondition == null ? criteria : new Criteria().andOperator(criteria, clubCondition));
    }

    private static List<ObjectId> objectIds(Collection<String> ids) {
        return ids.stream().filter(Objects::nonNull).filter(ObjectId::isValid).distinct().map(ObjectId::new).toList();
    }

    /**
     * Recruitment open across campus right now (published and not finished), earliest deadline first. Drives of
     * suspended or archived clubs are left out. {@code eligible} says whether the signed-in viewer could apply.
     */
    public List<OpenDriveItem> listOpenDrives(AuthUser actor) {
        Instant now = DrivePhases.now();
        Set<String> paused = pausedClubs.ids();
        Query query = openDrivesQuery(paused.isEmpty() ? null : Criteria.where("club").nin(objectIds(paused)), now)
                .with(Sort.by(Sort.Direction.ASC, "applicationEnd")).limit(50);
        query.fields().include("club").include("title").include("status").include("eligibility").include("applicationStart").include("applicationEnd")
                .include("closedAt").include("positions._id").include("positions.title").include("positions.finalizedAt").include("positions.rounds._id");
        List<RecruitmentDrive> list = mongo.find(query, RecruitmentDrive.class);
        if (list.isEmpty()) {
            return List.of();
        }
        List<ObjectId> clubIds = objectIds(list.stream().map(RecruitmentDrive::getClub).toList());
        Query clubQuery = Query.query(Criteria.where("_id").in(clubIds));
        clubQuery.fields().include("name").include("logo").include("category").include("allDepartments").include("departmentCodes").include("coverImage").include("status");
        Map<String, Club> clubs = mongo.find(clubQuery, Club.class).stream().collect(Collectors.toMap(Club::getId, Function.identity()));
        Set<String> memberOf = actor != null && ClubAccess.isStudent(actor) ? memberClubIds(actor.id(), clubIds) : Set.of();
        List<OpenDriveItem> items = new ArrayList<>();
        for (RecruitmentDrive drive : list) {
            Club club = clubs.get(drive.getClub());
            if (club == null) {
                continue;
            }
            Boolean eligible = null;
            if (actor != null) {
                String problem = ApplyRules.problem(actor, drive, club, memberOf.contains(club.getId()), now);
                eligible = problem == null || problem.startsWith("Applications open");
            }
            items.add(new OpenDriveItem(drive.getId(), drive.getTitle(), clubSummary(club), titles(drive), drive.getApplicationStart(), drive.getApplicationEnd(),
                    drive.getClosedAt(), DrivePhases.phaseOf(drive, now), eligible));
        }
        return items;
    }

    private Set<String> memberClubIds(String userId, List<ObjectId> clubIds) {
        Query query = Query.query(Criteria.where("user").is(new ObjectId(userId)).and("status").is(MembershipStatus.APPROVED.name()).and("club").in(clubIds));
        query.fields().include("club");
        return mongo.find(query, Document.class, "clubmemberships").stream().map(row -> row.getObjectId("club").toHexString()).collect(Collectors.toSet());
    }

    /**
     * For each club id, its drive currently taking applications ("Recruiting" badges on club cards and the club
     * page). Suspended and archived clubs have none.
     */
    public Map<String, OpenDrive> openDrivesByClub(Collection<String> clubIds) {
        Instant now = DrivePhases.now();
        Set<String> paused = pausedClubs.ids();
        List<ObjectId> ids = objectIds(clubIds.stream().filter(id -> !paused.contains(id)).toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Query query = openDrivesQuery(Criteria.where("club").in(ids), now);
        query.fields().include("club").include("title").include("applicationStart").include("applicationEnd");
        Map<String, OpenDrive> result = new LinkedHashMap<>();
        for (Document drive : mongo.find(query, Document.class, "recruitmentdrives")) {
            Instant start = drive.getDate("applicationStart") == null ? null : drive.getDate("applicationStart").toInstant();
            Instant end = drive.getDate("applicationEnd") == null ? null : drive.getDate("applicationEnd").toInstant();
            result.put(drive.getObjectId("club").toHexString(), new OpenDrive(drive.getObjectId("_id").toHexString(), drive.getString("title"), start, end,
                    start != null && !start.isAfter(now)));
        }
        return result;
    }

    /** Drives waiting for this faculty member's review. */
    public List<ReviewItem> listDrivesToReview(AuthUser actor) {
        Query clubQuery = Query.query(Criteria.where("mentor").is(new ObjectId(actor.id())));
        clubQuery.fields().include("_id").include("name").include("logo");
        Map<String, Map<String, Object>> clubs = new LinkedHashMap<>();
        mongo.find(clubQuery, Document.class, "clubs").forEach(club -> clubs.put(club.getObjectId("_id").toHexString(), Refs.shape(club, "name", "logo")));
        if (clubs.isEmpty()) {
            return List.of();
        }
        Query query = Query.query(Criteria.where("club").in(objectIds(clubs.keySet())).and("status").is(RecruitmentStatus.PENDING_APPROVAL.name()))
                .with(Sort.by(Sort.Direction.ASC, "submittedAt"));
        return mongo.find(query, RecruitmentDrive.class).stream()
                .map(drive -> new ReviewItem(drive.getId(), drive.getTitle(), clubs.get(drive.getClub()), titles(drive),
                        drive.getPositions().stream().mapToInt(position -> DrivePhases.questionsOf(position).size()).sum(), drive.getApplicationEnd(), drive.getSubmittedAt()))
                .toList();
    }

    // ---------------------------------------------------------------- Lifecycle

    private void record(AuditAction action, AuthUser actor, RecruitmentDrive drive, RecruitmentStatus from, RecruitmentStatus to, String reason, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clubId", new ObjectId(drive.getClub()));
        metadata.putAll(extra);
        audit.record(action, actor.id(), TARGET, drive.getId(), from == null ? null : from.name(), to == null ? null : to.name(), reason, metadata);
    }

    private boolean viceTaken(String clubId) {
        return mongo.exists(Query.query(Criteria.where("club").is(new ObjectId(clubId)).and("role").is(ClubRoles.VICE_PRESIDENT).and("status").is(MembershipStatus.APPROVED.name())),
                ClubMembership.class);
    }

    private Query activeDrives(String clubId) {
        return Query.query(Criteria.where("club").is(new ObjectId(clubId)).and("status").in(names(ACTIVE)));
    }

    private static void apply(RecruitmentDrive drive, DriveForms.NormalizedDrive normalized) {
        drive.setTitle(normalized.title());
        drive.setDescription(normalized.description());
        drive.setPositions(new ArrayList<>(normalized.positions()));
        RecruitmentEligibility eligibility = new RecruitmentEligibility();
        eligibility.setBatches(new ArrayList<>(normalized.batches()));
        drive.setEligibility(eligibility);
        drive.setApplicationStart(normalized.window().applicationStart());
        drive.setApplicationEnd(normalized.window().applicationEnd());
    }

    public DriveView createDrive(AuthUser actor, String clubId, Map<String, Object> payload) {
        Drives.Context context = drives.context(actor, clubAccess.loadClub(clubId));
        if (!context.canManage()) {
            throw AppException.forbidden("Only the club president can start a recruitment drive");
        }
        Club club = context.clubDocument();
        if (club.getStatus() != ClubStatus.ACTIVE) {
            throw AppException.conflict("Only active clubs can recruit", ErrorCode.CLUB_NOT_ACTIVE);
        }
        String oneAtATime = "This club already has a recruitment drive in progress. Finish or cancel it first.";
        if (mongo.exists(activeDrives(club.getId()), RecruitmentDrive.class)) {
            throw invalidState(oneAtATime);
        }
        DriveForms.NormalizedDrive normalized = DriveForms.normalize(club, payload == null ? Map.of() : payload, () -> viceTaken(club.getId()), DrivePhases.now());
        RecruitmentDrive draft = new RecruitmentDrive();
        apply(draft, normalized);
        draft.setClub(club.getId());
        draft.setCreatedBy(actor.id());
        // One drive at a time, even when two drafts are saved at the same moment.
        RecruitmentDrive drive = locks.withClubLock(club.getId(), () -> {
            if (mongo.exists(activeDrives(club.getId()), RecruitmentDrive.class)) {
                throw invalidState(oneAtATime);
            }
            return drives.insert(draft);
        });
        record(AuditAction.RECRUITMENT_CREATED, actor, drive, null, RecruitmentStatus.DRAFT, null, Map.of());
        return getDrive(actor, drive.getId());
    }

    /** The drive as {@code drive.toObject()} gave it, for merging an edit over it. */
    private Map<String, Object> current(RecruitmentDrive drive) {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("title", drive.getTitle());
        current.put("description", drive.getDescription());
        current.put("positions", json.convertValue(drive.getPositions(), List.class));
        List<String> batches = drive.getEligibility() == null ? null : drive.getEligibility().getBatches();
        current.put("eligibility", Map.of("batches", batches == null ? List.of() : batches));
        current.put("applicationStart", drive.getApplicationStart());
        current.put("applicationEnd", drive.getApplicationEnd());
        return current;
    }

    public DriveView updateDrive(AuthUser actor, String driveId, Map<String, Object> payload) {
        RecruitmentDrive drive = drives.load(driveId);
        Club club = drives.assertManager(actor, drive).clubDocument();
        String notEditable = "A drive can only be edited while it's a draft or has changes requested";
        if (!EDITABLE.contains(drive.getStatus())) {
            throw invalidState(notEditable);
        }
        Map<String, Object> merged = current(drive);
        if (payload != null) {
            merged.putAll(payload);
        }
        DriveForms.NormalizedDrive normalized = DriveForms.normalize(club, merged, () -> viceTaken(club.getId()), DrivePhases.now());
        Update update = new Update()
                .set("title", normalized.title())
                .set("description", normalized.description())
                .set("positions", normalized.positions().stream().map(drives::toDocument).toList())
                .set("eligibility", new Document("batches", normalized.batches()))
                .set("applicationStart", normalized.window().applicationStart())
                .set("applicationEnd", normalized.window().applicationEnd());
        if (!drives.update(driveId, Criteria.where("status").in(names(EDITABLE)), update)) {
            throw invalidState(notEditable);
        }
        record(AuditAction.RECRUITMENT_UPDATED, actor, drive, null, null, null, Map.of());
        return getDrive(actor, driveId);
    }

    public void deleteDraft(AuthUser actor, String driveId) {
        RecruitmentDrive drive = drives.load(driveId);
        drives.assertManager(actor, drive);
        String onlyDrafts = "Only drafts can be deleted. Cancel the drive instead.";
        if (drive.getStatus() != RecruitmentStatus.DRAFT || !drives.remove(driveId, Criteria.where("status").is(RecruitmentStatus.DRAFT.name()))) {
            throw invalidState(onlyDrafts);
        }
    }

    public DriveView submitDrive(AuthUser actor, String driveId) {
        RecruitmentDrive drive = drives.load(driveId);
        Club club = drives.assertManager(actor, drive).clubDocument();
        String onlyDrafts = "Only drafts can be sent for approval";
        if (!EDITABLE.contains(drive.getStatus())) {
            throw invalidState(onlyDrafts);
        }
        if (club.getMentor() == null) {
            throw invalidState("Your club needs a faculty mentor to approve recruitment");
        }
        Instant now = DrivePhases.now();
        DriveForms.window(drive.getApplicationStart() == null ? now : drive.getApplicationStart(), drive.getApplicationEnd(), now);
        RecruitmentStatus from = drive.getStatus();
        if (!drives.update(driveId, Criteria.where("status").in(names(EDITABLE)),
                new Update().set("status", RecruitmentStatus.PENDING_APPROVAL.name()).set("submittedAt", now))) {
            throw invalidState(onlyDrafts);
        }
        record(AuditAction.RECRUITMENT_SUBMITTED, actor, drive, from, RecruitmentStatus.PENDING_APPROVAL, null, Map.of());
        notifications.notify(club.getMentor(), Notice.builder()
                .type(NotificationType.RECRUITMENT_REVIEW)
                .title("Recruitment to review: " + drive.getTitle())
                .message(club.getName() + " wants to recruit " + String.join(", ", titles(drive)) + ". Review each role's form before it's published.")
                .link(RecruitmentMailer.drivePath(drive))
                .email(true)
                .build());
        return getDrive(actor, driveId);
    }

    /** The mentor's decision on a drive waiting for review; the president is told (and e-mailed). */
    private DriveView review(AuthUser actor, String driveId, String comment, RecruitmentStatus status, AuditAction action, boolean required,
            Function<RecruitmentDrive, String> title, Function<String, String> message) {
        RecruitmentDrive drive = drives.load(driveId);
        Drives.Context context = drives.context(actor, drive);
        if (!context.isMentor()) {
            throw AppException.forbidden("Only the club's faculty mentor can review recruitment");
        }
        String notWaiting = "This drive isn't waiting for review";
        if (drive.getStatus() != RecruitmentStatus.PENDING_APPROVAL) {
            throw invalidState(notWaiting);
        }
        String note = Js.clean(comment, 2000);
        if (required && note.length() < 5) {
            throw invalid("Explain what needs to change (at least 5 characters)");
        }
        Update update = new Update().set("status", status.name()).set("reviewedBy", new ObjectId(actor.id())).set("reviewedAt", DrivePhases.now())
                .set("reviewComment", note.isEmpty() ? null : note);
        if (!drives.update(driveId, Criteria.where("status").is(RecruitmentStatus.PENDING_APPROVAL.name()), update)) {
            throw invalidState(notWaiting);
        }
        record(action, actor, drive, RecruitmentStatus.PENDING_APPROVAL, status, note.isEmpty() ? null : note, Map.of());
        String clubId = context.clubDocument().getId();
        notifications.notify(clubAccess.usersWithPermission(clubId, ClubPermission.MANAGE_RECRUITMENT), Notice.builder()
                .type(NotificationType.RECRUITMENT_UPDATE)
                .title(title.apply(drive))
                .message(message.apply(note))
                .link(RecruitmentMailer.drivePath(drive))
                .email(true)
                .build());
        return getDrive(actor, driveId);
    }

    public DriveView approveDrive(AuthUser actor, String driveId, String comment) {
        return review(actor, driveId, comment, RecruitmentStatus.APPROVED, AuditAction.RECRUITMENT_APPROVED, false,
                drive -> "Recruitment approved: " + drive.getTitle(),
                note -> "Your faculty mentor approved it" + (note.isEmpty() ? "" : ": \"" + note + "\"") + ". Publish it to open applications.");
    }

    public DriveView requestDriveChanges(AuthUser actor, String driveId, String comment) {
        return review(actor, driveId, comment, RecruitmentStatus.NEEDS_CHANGES, AuditAction.RECRUITMENT_CHANGES_REQUESTED, true,
                drive -> "Changes requested: " + drive.getTitle(), note -> note);
    }

    public DriveView rejectDrive(AuthUser actor, String driveId, String comment) {
        return review(actor, driveId, comment, RecruitmentStatus.REJECTED, AuditAction.RECRUITMENT_REJECTED, true,
                drive -> "Recruitment not approved: " + drive.getTitle(), note -> note);
    }

    public DriveView publishDrive(AuthUser actor, String driveId) {
        RecruitmentDrive drive = drives.load(driveId);
        Club club = drives.assertManager(actor, drive).clubDocument();
        String onlyApproved = "Only drives approved by the faculty mentor can be published";
        if (drive.getStatus() != RecruitmentStatus.APPROVED) {
            throw invalidState(onlyApproved);
        }
        if (club.getStatus() != ClubStatus.ACTIVE) {
            throw AppException.conflict("Only active clubs can recruit", ErrorCode.CLUB_NOT_ACTIVE);
        }
        Instant now = DrivePhases.now();
        if (drive.getApplicationEnd() == null || !drive.getApplicationEnd().isAfter(now)) {
            throw invalidState("The application deadline has passed. Edit the dates and send it for approval again.");
        }
        if (!drives.update(driveId, Criteria.where("status").is(RecruitmentStatus.APPROVED.name()),
                new Update().set("status", RecruitmentStatus.PUBLISHED.name()).set("publishedAt", now))) {
            throw invalidState(onlyApproved);
        }
        drive.setStatus(RecruitmentStatus.PUBLISHED);
        drive.setPublishedAt(now);
        record(AuditAction.RECRUITMENT_PUBLISHED, actor, drive, RecruitmentStatus.APPROVED, RecruitmentStatus.PUBLISHED, null, Map.of());
        mailer.sendRecruitmentLaunch(drive, club, actor);
        return getDrive(actor, driveId);
    }

    /** Matches drives none of whose roles has rounds or a final selection yet. */
    private static Criteria beforeSelection() {
        return Criteria.where("positions").not().elemMatch(new Criteria().orOperator(Criteria.where("finalizedAt").ne(null), Criteria.where("rounds.0").exists(true)));
    }

    /** President: move the deadline later (also reopens applications closed early), until rounds begin. */
    public DriveView extendDeadline(AuthUser actor, String driveId, String applicationEnd) {
        RecruitmentDrive drive = drives.load(driveId);
        drives.assertManager(actor, drive);
        String tooLate = "The deadline can only change before selection rounds begin";
        if (drive.getStatus() != RecruitmentStatus.PUBLISHED || DrivePhases.selectionStarted(drive)) {
            throw invalidState(tooLate);
        }
        Instant now = DrivePhases.now();
        Instant end = IsoTimes.parse(applicationEnd);
        if (end == null || !end.isAfter(now)) {
            throw invalid("The new deadline must be in the future");
        }
        Instant start = drive.getApplicationStart() == null ? Instant.EPOCH : drive.getApplicationStart();
        if (end.toEpochMilli() - start.toEpochMilli() > DriveForms.MAX_WINDOW_MILLIS) {
            throw invalid("Applications can stay open for up to " + DriveForms.MAX_WINDOW_DAYS + " days");
        }
        Criteria condition = new Criteria().andOperator(Criteria.where("status").is(RecruitmentStatus.PUBLISHED.name()), beforeSelection());
        if (!drives.update(driveId, condition, new Update().set("applicationEnd", end).set("closedAt", null))) {
            throw invalidState(tooLate);
        }
        record(AuditAction.RECRUITMENT_EXTENDED, actor, drive, null, null, null, Map.of("applicationEnd", end));
        return getDrive(actor, driveId);
    }

    /** President: stop taking applications before the deadline. */
    public DriveView closeApplications(AuthUser actor, String driveId) {
        RecruitmentDrive drive = drives.load(driveId);
        drives.assertManager(actor, drive);
        String closed = "Applications are already closed";
        if (!DrivePhases.takingApplications(drive, Instant.now())) {
            throw invalidState(closed);
        }
        if (!drives.update(driveId, Criteria.where("status").is(RecruitmentStatus.PUBLISHED.name()).and("closedAt").is(null),
                new Update().set("closedAt", DrivePhases.now()))) {
            throw invalidState(closed);
        }
        record(AuditAction.RECRUITMENT_CLOSED, actor, drive, null, null, null, Map.of());
        return getDrive(actor, driveId);
    }

    public DriveView cancelDrive(AuthUser actor, String driveId, String reason) {
        RecruitmentDrive drive = drives.load(driveId);
        Club club = drives.assertManager(actor, drive).clubDocument();
        String finished = "This drive is already finished";
        if (FINISHED.contains(drive.getStatus())) {
            throw invalidState(finished);
        }
        String note = Js.clean(reason, 500);
        RecruitmentStatus from = drive.getStatus();
        Instant now = DrivePhases.now();
        if (!drives.update(driveId, Criteria.where("status").nin(names(FINISHED)),
                new Update().set("status", RecruitmentStatus.CANCELLED.name()).set("cancelledAt", now).set("cancellationReason", note.isEmpty() ? null : note))) {
            throw invalidState(finished);
        }
        Criteria open = Criteria.where("drive").is(new ObjectId(driveId)).and("status").in(names(OPEN_APPLICATION));
        Query openQuery = Query.query(open);
        openQuery.fields().include("applicant");
        List<String> applicants = mongo.find(openQuery, RecruitmentApplication.class).stream().map(RecruitmentApplication::getApplicant).distinct().toList();
        mongo.updateMulti(Query.query(open),
                new Update().set("status", ApplicationStatus.NOT_SELECTED.name()).set("decidedAt", now).set("closedReason", "Recruitment cancelled").set("updatedAt", now),
                RecruitmentApplication.class);
        record(AuditAction.RECRUITMENT_CANCELLED, actor, drive, from, RecruitmentStatus.CANCELLED, note.isEmpty() ? null : note, Map.of());
        if (!applicants.isEmpty()) {
            mailer.sendDriveCancelled(drive, club, applicants, note);
        }
        return getDrive(actor, driveId);
    }
}
