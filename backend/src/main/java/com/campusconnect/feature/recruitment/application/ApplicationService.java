package com.campusconnect.feature.recruitment.application;

import static com.campusconnect.feature.recruitment.drive.DrivePhases.OPEN_APPLICATION;
import static com.campusconnect.feature.recruitment.drive.DrivePhases.names;
import static com.campusconnect.feature.recruitment.drive.Drives.invalid;
import static com.campusconnect.feature.recruitment.drive.Drives.invalidState;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.media.MediaStores;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.Texts;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.recruitment.AnswerFile;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RoundOutcome;
import com.campusconnect.feature.recruitment.drive.ApplyRules;
import com.campusconnect.feature.recruitment.drive.DriveCompletion;
import com.campusconnect.feature.recruitment.drive.DrivePhases;
import com.campusconnect.feature.recruitment.drive.Drives;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.ApplicantRound;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.DriveBrief;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.LocalUpload;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.MyApplication;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.MyApplicationRow;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.OtherApplication;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.StaffApplication;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.StaffApplicationDetail;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.StaffApplicationRow;
import com.campusconnect.feature.recruitment.mail.RecruitmentMailer;
import com.campusconnect.feature.recruitment.support.Js;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Applications to recruitment drives (services/ApplicationService.js). A student applies to a role by filling that
 * role's page-wise form while applications are open — one application per role, and they may apply to several
 * roles. They can edit or withdraw until the deadline and follow each application through its rounds. The club's
 * president and mentor see the applications; answers (and resume links) only on a single application.
 */
@Service
public class ApplicationService {

    static final String TARGET = "RecruitmentApplication";
    private static final List<ApplicationStatus> WITHDRAWABLE = List.of(ApplicationStatus.APPLIED, ApplicationStatus.IN_ROUNDS, ApplicationStatus.RESERVE);
    private static final String[] APPLICANT_FIELDS = {"name", "email", "departmentCode", "batchCode"};

    private final MongoOperations mongo;
    private final Drives drives;
    private final ClubAccess clubAccess;
    private final MediaStores media;
    private final AnswerReader answerReader;
    private final ApplicationViewer viewer;
    private final AuditService audit;
    private final NotificationService notifications;
    private final RecruitmentMailer mailer;
    private final DriveCompletion completion;
    private final Refs refs;

    public ApplicationService(MongoOperations mongo, Drives drives, ClubAccess clubAccess, MediaStores media, AnswerReader answerReader, ApplicationViewer viewer,
            AuditService audit, NotificationService notifications, RecruitmentMailer mailer, DriveCompletion completion, Refs refs) {
        this.mongo = mongo;
        this.drives = drives;
        this.clubAccess = clubAccess;
        this.media = media;
        this.answerReader = answerReader;
        this.viewer = viewer;
        this.audit = audit;
        this.notifications = notifications;
        this.mailer = mailer;
        this.completion = completion;
        this.refs = refs;
    }

    static ObjectId oid(String id) {
        return new ObjectId(id);
    }

    /** The student's applications as they see them, including ones closed because they joined in another role. */
    static Criteria visibleToApplicant() {
        return new Criteria().orOperator(Criteria.where("status").ne(ApplicationStatus.WITHDRAWN.name()), Criteria.where("closedReason").ne(null));
    }

    private String applyProblem(AuthUser actor, RecruitmentDrive drive, Club club, Instant now) {
        boolean member = ClubAccess.isStudent(actor) && clubAccess.membership(actor.id(), club.getId()) != null;
        return ApplyRules.problem(actor, drive, club, member, now);
    }

    private void audit(AuditAction action, AuthUser actor, String applicationId, Map<String, Object> metadata) {
        audit.record(action, actor.id(), TARGET, applicationId, metadata);
    }

    private void deleteQuietly(List<AnswerFile> files) {
        files.forEach(file -> media.recruitment().deleteMediaQuietly(file));
    }

    private List<Document> answerDocuments(List<?> answers) {
        return answers.stream().map(drives::toDocument).toList();
    }

    // ---------------------------------------------------------------- Uploads

    public List<MediaStore.UploadTicket> createUploadTickets(AuthUser actor, String driveId, List<String> kinds) {
        RecruitmentDrive drive = drives.load(driveId);
        Club club = clubAccess.loadClub(drive.getClub());
        String problem = applyProblem(actor, drive, club, Instant.now());
        if (problem != null) {
            throw AppException.forbidden(problem);
        }
        List<String> list = kinds == null ? List.of() : kinds.subList(0, Math.min(10, kinds.size()));
        if (list.isEmpty() || list.stream().anyMatch(kind -> !"DOCUMENT".equals(kind) && !"IMAGE".equals(kind))) {
            throw invalid("Attach PDFs or images");
        }
        return list.stream().map(kind -> media.recruitment().createUploadTicket(drive.getId(), actor.id(), MediaKind.valueOf(kind), null)).toList();
    }

    /** Development storage: the file is posted to this API and attached to an answer with the returned token. */
    public LocalUpload uploadLocalFile(AuthUser actor, String driveId, MultipartFile file) {
        MediaStore store = media.recruitment();
        long max = store.maxBytesFor(MediaKind.DOCUMENT);
        if (file != null && file.getSize() > max) {
            throw AppException.of(413, "Files must be " + Math.round(max / (1024d * 1024d)) + " MB or smaller", ErrorCode.UPLOAD_ERROR);
        }
        if (!"local".equals(store.providerName())) {
            throw AppException.badRequest("Files are uploaded directly to cloud storage", ErrorCode.UPLOAD_ERROR);
        }
        RecruitmentDrive drive = drives.load(driveId);
        byte[] bytes;
        try {
            bytes = file == null || file.isEmpty() ? null : file.getBytes();
        } catch (IOException e) {
            throw AppException.badRequest("Invalid upload", ErrorCode.UPLOAD_ERROR);
        }
        MediaStore.LocalUpload saved = store.saveLocalFile(bytes, file == null ? null : file.getOriginalFilename(), drive.getId(), actor.id(), null, null);
        return new LocalUpload("local", saved.token(), saved.kind(), saved.bytes(), saved.format());
    }

    // ---------------------------------------------------------------- The student's applications

    private Map<String, Map<String, Object>> venues(RecruitmentDrive drive) {
        return drives.venues(List.of(drive), "name", "location");
    }

    /** The student's applications in this drive, one per role. */
    public List<MyApplication> getMyApplications(AuthUser actor, String driveId) {
        RecruitmentDrive drive = drives.load(driveId);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("drive").is(oid(drive.getId())).and("applicant").is(oid(actor.id())), visibleToApplicant())).with(Sort.by(Sort.Direction.ASC, "createdAt", "_id"));
        Map<String, Map<String, Object>> venues = venues(drive);
        Instant now = DrivePhases.now();
        return mongo.find(query, RecruitmentApplication.class).stream().map(application -> viewer.mine(drive, application, venues, now)).toList();
    }

    private RecruitmentApplication findMine(RecruitmentDrive drive, AuthUser actor, String positionId) {
        return mongo.findOne(Query.query(Criteria.where("drive").is(oid(drive.getId())).and("applicant").is(oid(actor.id())).and("position").is(oid(positionId))),
                RecruitmentApplication.class);
    }

    public MyApplication getMyApplication(AuthUser actor, String driveId, String positionId) {
        RecruitmentDrive drive = drives.load(driveId);
        RecruitmentApplication application = findMine(drive, actor, positionId);
        if (application == null || application.getStatus() == ApplicationStatus.WITHDRAWN) {
            throw AppException.notFound("You haven't applied for this role");
        }
        return viewer.mine(drive, application, venues(drive), DrivePhases.now());
    }

    public MyApplication apply(AuthUser actor, String driveId, String positionId, List<?> input) {
        RecruitmentDrive drive = drives.load(driveId);
        RecruitmentPosition position = Drives.position(drive, positionId);
        Club club = clubAccess.loadClub(drive.getClub());
        String problem = applyProblem(actor, drive, club, Instant.now());
        if (problem != null) {
            throw AppException.forbidden(problem);
        }
        RecruitmentApplication existing = findMine(drive, actor, position.getId());
        if (existing != null && existing.getStatus() != ApplicationStatus.WITHDRAWN) {
            throw AppException.conflict("You've already applied for " + position.getTitle() + ". You can edit your application until the deadline.", ErrorCode.CONFLICT);
        }
        AnswerReader.Answers read = answerReader.read(drive, position, actor, input, List.of());
        String duplicate = "You've already applied for " + position.getTitle();
        RecruitmentApplication application;
        try {
            if (existing != null) {
                application = mongo.findAndModify(
                        Query.query(Criteria.where("_id").is(oid(existing.getId())).and("status").is(ApplicationStatus.WITHDRAWN.name())),
                        new Update().set("answers", answerDocuments(read.answers())).set("status", ApplicationStatus.APPLIED.name()).set("roundResults", List.of())
                                .set("slots", List.of()).set("remindersSent", List.of()).set("withdrawnAt", null).set("decidedAt", null).set("closedReason", null)
                                .set("offeredAt", null).set("offerExpiresAt", null).set("respondedAt", null).set("updatedAt", Instant.now()),
                        FindAndModifyOptions.options().returnNew(true), RecruitmentApplication.class);
                if (application == null) {
                    throw AppException.conflict(duplicate, ErrorCode.CONFLICT);
                }
            } else {
                RecruitmentApplication created = new RecruitmentApplication();
                created.setDrive(drive.getId());
                created.setClub(club.getId());
                created.setApplicant(actor.id());
                created.setPosition(position.getId());
                created.setAnswers(new ArrayList<>(read.answers()));
                application = mongo.insert(created);
            }
        } catch (DuplicateKeyException e) {
            deleteQuietly(read.newFiles());
            throw AppException.conflict(duplicate, ErrorCode.CONFLICT);
        } catch (RuntimeException e) {
            deleteQuietly(read.newFiles());
            throw e;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("driveId", oid(drive.getId()));
        metadata.put("clubId", oid(club.getId()));
        metadata.put("role", position.getRole());
        audit(AuditAction.APPLICATION_SUBMITTED, actor, application.getId(), metadata);
        mailer.sendApplicationReceived(drive, club, actor.id(), position.getTitle());
        notifications.notify(clubAccess.usersWithPermission(club.getId(), ClubPermission.MANAGE_RECRUITMENT), Notice.builder()
                .type(NotificationType.APPLICATION_UPDATE)
                .title("New application: " + position.getTitle())
                .message(actor.name() + " applied for " + position.getTitle() + " (" + drive.getTitle() + ").")
                .link("/recruitment/" + drive.getId() + "?tab=applications")
                .build());
        return viewer.mine(drive, application, venues(drive), DrivePhases.now());
    }

    public MyApplication updateMyApplication(AuthUser actor, String driveId, String positionId, List<?> input) {
        RecruitmentDrive drive = drives.load(driveId);
        RecruitmentPosition position = Drives.position(drive, positionId);
        RecruitmentApplication application = findMine(drive, actor, position.getId());
        String notApplied = "You haven't applied for this role";
        if (application == null || application.getStatus() != ApplicationStatus.APPLIED) {
            throw AppException.notFound(notApplied);
        }
        if (!DrivePhases.applicationsOpen(drive, Instant.now())) {
            throw invalidState("Applications are closed, so your answers can't change any more");
        }
        AnswerReader.Answers read = answerReader.read(drive, position, actor, input, application.getAnswers());
        RecruitmentApplication updated;
        try {
            updated = mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(oid(application.getId())).and("status").is(ApplicationStatus.APPLIED.name())),
                    new Update().set("answers", answerDocuments(read.answers())).set("updatedAt", Instant.now()),
                    FindAndModifyOptions.options().returnNew(true), RecruitmentApplication.class);
        } catch (RuntimeException e) {
            deleteQuietly(read.newFiles());
            throw e;
        }
        if (updated == null) {
            deleteQuietly(read.newFiles());
            throw AppException.notFound(notApplied);
        }
        deleteQuietly(read.droppedFiles());
        audit(AuditAction.APPLICATION_UPDATED, actor, updated.getId(), Map.of("driveId", oid(drive.getId())));
        return viewer.mine(drive, updated, venues(drive), DrivePhases.now());
    }

    public void withdraw(AuthUser actor, String driveId, String positionId) {
        RecruitmentDrive drive = drives.load(driveId);
        Instant now = DrivePhases.now();
        RecruitmentApplication application = mongo.findAndModify(
                Query.query(Criteria.where("drive").is(oid(drive.getId())).and("applicant").is(oid(actor.id())).and("position").is(oid(positionId))
                        .and("status").in(names(WITHDRAWABLE))),
                new Update().set("status", ApplicationStatus.WITHDRAWN.name()).set("withdrawnAt", now).set("pendingOutcome", clearedOutcome()).set("slots", List.of())
                        .set("updatedAt", now),
                RecruitmentApplication.class);
        if (application == null) {
            throw AppException.notFound("There's no application to withdraw for this role");
        }
        audit(AuditAction.APPLICATION_WITHDRAWN, actor, application.getId(), Map.of("driveId", oid(drive.getId())));
        completion.completeIfDone(drive.getId());
    }

    /** {@code pendingOutcome} with no draft decision, as stored by the original app. */
    static Document clearedOutcome() {
        Document outcome = new Document();
        outcome.put("round", null);
        outcome.put("outcome", null);
        outcome.put("note", null);
        return outcome;
    }

    /** Every application of the signed-in student, newest first, with what's next. */
    public List<MyApplicationRow> myApplications(AuthUser actor) {
        Query query = Query.query(new Criteria().andOperator(Criteria.where("applicant").is(oid(actor.id())), visibleToApplicant()))
                .with(Sort.by(Sort.Direction.DESC, "createdAt", "_id")).limit(40);
        query.fields().exclude("answers").exclude("remindersSent");
        List<RecruitmentApplication> applications = mongo.find(query, RecruitmentApplication.class);
        if (applications.isEmpty()) {
            return List.of();
        }
        Query driveQuery = Query.query(Criteria.where("_id").in(applications.stream().map(RecruitmentApplication::getDrive).distinct().map(ObjectId::new).toList()));
        driveQuery.fields().exclude("positions.form").exclude("description");
        Map<String, RecruitmentDrive> driveById = mongo.find(driveQuery, RecruitmentDrive.class).stream().collect(Collectors.toMap(RecruitmentDrive::getId, Function.identity()));
        Map<String, Map<String, Object>> clubs = refs.clubs(driveById.values().stream().map(RecruitmentDrive::getClub).toList(), "name", "logo");
        Map<String, Map<String, Object>> venues = drives.venues(driveById.values(), "name", "location");
        Instant now = DrivePhases.now();
        List<MyApplicationRow> rows = new ArrayList<>();
        for (RecruitmentApplication application : applications) {
            RecruitmentDrive drive = driveById.get(application.getDrive());
            RecruitmentPosition position = drive == null ? null : Drives.positionOrNull(drive, application.getPosition());
            if (position == null) {
                continue;
            }
            List<ApplicantRound> rounds = viewer.roundsFor(position, application, venues);
            ApplicantRound next = rounds.stream()
                    .filter(round -> round.slot() != null && round.slot().endAt() != null && round.slot().endAt().isAfter(now) && round.result() == null)
                    .min(Comparator.comparing(round -> round.slot().startAt()))
                    .orElse(null);
            rows.add(new MyApplicationRow(application.getId(), application.getStatus(), application.getCreatedAt(), position.getId(), position.getTitle(),
                    application.getOfferExpiresAt(), application.getClosedReason(),
                    new DriveBrief(drive.getId(), drive.getTitle(), drive.getStatus(), DrivePhases.phaseOf(drive, now), drive.getApplicationEnd(), clubs.get(drive.getClub())),
                    (int) application.getRoundResults().stream().filter(result -> result.getOutcome() == RoundOutcome.QUALIFIED).count(),
                    next));
        }
        return rows;
    }

    // ---------------------------------------------------------------- Club side

    static StaffApplication staffView(RecruitmentDrive drive, RecruitmentApplication application, Map<String, Map<String, Object>> applicants) {
        return new StaffApplication(
                application.getId(),
                applicants.get(application.getApplicant()),
                application.getStatus(),
                application.getPosition(),
                Objects.requireNonNullElse(Drives.positionTitle(drive, application.getPosition()), "Role"),
                application.getRoundResults(),
                application.getPendingOutcome() != null && application.getPendingOutcome().getOutcome() != null ? application.getPendingOutcome() : null,
                application.getSlots(),
                application.getOfferExpiresAt(),
                application.getClosedReason(),
                application.getCreatedAt());
    }

    /** President and mentor: the applications, searchable by name or email and filterable by role and status. */
    public List<StaffApplicationRow> listApplications(AuthUser actor, String driveId, String status, String position, String search) {
        RecruitmentDrive drive = drives.load(driveId);
        drives.assertStaff(actor, drive);
        ObjectId id = oid(drive.getId());
        Criteria filter = Criteria.where("drive").is(id);
        if ("active".equals(status)) {
            filter = filter.and("status").in(names(OPEN_APPLICATION));
        } else if (status != null && Arrays.stream(ApplicationStatus.values()).anyMatch(value -> value.name().equals(status))) {
            filter = filter.and("status").is(status);
        } else {
            filter = filter.and("status").ne(ApplicationStatus.WITHDRAWN.name());
        }
        if (position != null && !position.isEmpty()) {
            filter = filter.and("position").is(oid(position));
        }
        if (search != null && !Js.trim(search).isEmpty()) {
            List<ObjectId> applicantIds = mongo.findDistinct(Query.query(Criteria.where("drive").is(id)), "applicant", RecruitmentApplication.class, ObjectId.class);
            Query users = Query.query(new Criteria().andOperator(Criteria.where("_id").in(applicantIds),
                    new Criteria().orOperator(Texts.search("name", search), Texts.search("email", search))));
            users.fields().include("_id");
            filter = filter.and("applicant").in(mongo.find(users, Document.class, "users").stream().map(user -> user.getObjectId("_id")).toList());
        }
        Query query = Query.query(filter).with(Sort.by(Sort.Direction.ASC, "createdAt", "_id")).limit(500);
        query.fields().exclude("answers").exclude("remindersSent");
        List<RecruitmentApplication> applications = mongo.find(query, RecruitmentApplication.class);
        Map<String, Map<String, Object>> applicants = refs.users(applications.stream().map(RecruitmentApplication::getApplicant).toList(), APPLICANT_FIELDS);
        Map<String, Integer> perStudent = applicationsPerStudent(id);
        return applications.stream()
                .map(application -> new StaffApplicationRow(staffView(drive, application, applicants),
                        (applicants.containsKey(application.getApplicant()) ? perStudent.getOrDefault(application.getApplicant(), 1) : 1) - 1))
                .toList();
    }

    /** How many roles each student applied for, so the president can see overlaps. */
    private Map<String, Integer> applicationsPerStudent(ObjectId driveId) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("drive").is(driveId).and("status").ne(ApplicationStatus.WITHDRAWN.name())),
                Aggregation.group("applicant").count().as("count"));
        Map<String, Integer> counts = new HashMap<>();
        for (Document row : mongo.aggregate(aggregation, "recruitmentapplications", Document.class).getMappedResults()) {
            Object applicant = row.get("_id");
            if (applicant instanceof ObjectId objectId) {
                counts.put(objectId.toHexString(), ((Number) row.get("count")).intValue());
            }
        }
        return counts;
    }

    public StaffApplicationDetail getApplication(AuthUser actor, String driveId, String applicationId) {
        RecruitmentDrive drive = drives.load(driveId);
        drives.assertStaff(actor, drive);
        RecruitmentApplication application = mongo.findOne(Query.query(Criteria.where("_id").is(oid(applicationId)).and("drive").is(oid(drive.getId()))),
                RecruitmentApplication.class);
        RecruitmentPosition position = application == null ? null : Drives.positionOrNull(drive, application.getPosition());
        if (position == null) {
            throw AppException.notFound("Application not found");
        }
        Map<String, Map<String, Object>> applicants = refs.users(List.of(application.getApplicant()), APPLICANT_FIELDS);
        Query othersQuery = Query.query(Criteria.where("drive").is(oid(drive.getId())).and("applicant").is(oid(application.getApplicant()))
                .and("_id").ne(oid(application.getId())).and("status").ne(ApplicationStatus.WITHDRAWN.name()));
        othersQuery.fields().include("position").include("status");
        List<OtherApplication> others = mongo.find(othersQuery, RecruitmentApplication.class).stream()
                .map(other -> new OtherApplication(other.getPosition(), Drives.positionTitle(drive, other.getPosition()), other.getStatus()))
                .toList();
        return new StaffApplicationDetail(staffView(drive, application, applicants), viewer.answerPages(position, application), others);
    }
}
