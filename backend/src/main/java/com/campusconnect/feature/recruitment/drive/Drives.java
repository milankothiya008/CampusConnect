package com.campusconnect.feature.recruitment.drive;

import static com.campusconnect.feature.recruitment.drive.DrivePhases.ACTIVE_APPLICATION;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubContext;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.cache.SharedCaches;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.feature.recruitment.dto.DriveViews.Counts;
import com.campusconnect.feature.recruitment.dto.DriveViews.DriveCounts;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Loading, authorising and writing recruitment drives, shared by the drive, application and selection services.
 * Every write clears the cached clubs directory, whose cards show "Recruiting" (the Node clearOnWrite hook).
 * Changes to roles and rounds are targeted updates of those sub-documents, so they never overwrite the drive's
 * status or another role changed at the same moment.
 */
@Component
public class Drives {

    public static final String MANAGE_DENIED = "Only the club president can manage recruitment";

    private final MongoOperations mongo;
    private final ClubAccess clubAccess;
    private final SharedCaches caches;
    private final Refs refs;

    public Drives(MongoOperations mongo, ClubAccess clubAccess, SharedCaches caches, Refs refs) {
        this.mongo = mongo;
        this.clubAccess = clubAccess;
        this.caches = caches;
        this.refs = refs;
    }

    /** The viewer's standing with the drive's club: president (manage) or mentor (review, read). */
    public record Context(ClubContext club, boolean canManage, boolean isMentor) {

        public Club clubDocument() {
            return club.club();
        }

        public boolean isStaff() {
            return canManage || isMentor;
        }
    }

    /** A published drive whose selection the president is running, the role and the club. */
    public record Running(RecruitmentDrive drive, RecruitmentPosition position, Club club) {
    }

    // ---------------------------------------------------------------- loading

    public RecruitmentDrive load(String driveId) {
        RecruitmentDrive drive = ObjectId.isValid(driveId) ? mongo.findById(driveId, RecruitmentDrive.class) : null;
        if (drive == null) {
            throw AppException.notFound("Recruitment drive not found");
        }
        return drive;
    }

    public static RecruitmentPosition positionOrNull(RecruitmentDrive drive, String positionId) {
        if (positionId == null) {
            return null;
        }
        return drive.getPositions().stream().filter(position -> positionId.equals(position.getId())).findFirst().orElse(null);
    }

    public static RecruitmentPosition position(RecruitmentDrive drive, String positionId) {
        RecruitmentPosition position = positionOrNull(drive, positionId);
        if (position == null) {
            throw AppException.notFound("This drive isn't recruiting for that role");
        }
        return position;
    }

    public static String positionTitle(RecruitmentDrive drive, String positionId) {
        RecruitmentPosition position = positionOrNull(drive, positionId);
        return position == null ? null : position.getTitle();
    }

    // ---------------------------------------------------------------- authorisation

    public Context context(AuthUser actor, RecruitmentDrive drive) {
        return context(actor, clubAccess.loadClub(drive.getClub()));
    }

    public Context context(AuthUser actor, Club club) {
        ClubContext context = clubAccess.context(actor, club);
        return new Context(context, context.has(ClubPermission.MANAGE_RECRUITMENT), context.isMentor());
    }

    public Context assertManager(AuthUser actor, RecruitmentDrive drive) {
        Context context = context(actor, drive);
        if (!context.canManage()) {
            throw AppException.forbidden(MANAGE_DENIED);
        }
        return context;
    }

    public Context assertStaff(AuthUser actor, RecruitmentDrive drive) {
        Context context = context(actor, drive);
        if (!context.isStaff()) {
            throw AppException.forbidden("Only the club president and faculty mentor can see applications");
        }
        return context;
    }

    /** The president runs a role's selection while the drive is published, its club active and applications closed. */
    public Running running(AuthUser actor, String driveId, String positionId) {
        RecruitmentDrive drive = load(driveId);
        Context context = assertManager(actor, drive);
        if (drive.getStatus() != RecruitmentStatus.PUBLISHED) {
            throw invalidState("Selection runs while the drive is published");
        }
        Club club = context.clubDocument();
        if (club.getStatus() != ClubStatus.ACTIVE) {
            throw invalidState(club.getName() + " is " + club.getStatus().name().toLowerCase(Locale.ROOT) + ", so selection is on hold");
        }
        if (DrivePhases.takingApplications(drive, Instant.now())) {
            throw invalidState("Close applications before starting the selection rounds");
        }
        return new Running(drive, position(drive, positionId), club);
    }

    public static AppException invalidState(String message) {
        return AppException.conflict(message, ErrorCode.INVALID_STATE);
    }

    public static AppException invalid(String message) {
        return AppException.badRequest(message, ErrorCode.VALIDATION_ERROR);
    }

    // ---------------------------------------------------------------- writes

    public RecruitmentDrive insert(RecruitmentDrive drive) {
        RecruitmentDrive saved = mongo.insert(drive);
        caches.clearClubDirectory();
        return saved;
    }

    /** One atomic update of the drive, applied only while {@code condition} still holds; false when it did not. */
    public boolean update(String driveId, Criteria condition, Update update) {
        Criteria criteria = Criteria.where("_id").is(new ObjectId(driveId));
        if (condition != null) {
            criteria = new Criteria().andOperator(criteria, condition);
        }
        update.set("updatedAt", Instant.now());
        boolean matched = mongo.updateFirst(Query.query(criteria), update, RecruitmentDrive.class).getMatchedCount() > 0;
        caches.clearClubDirectory();
        return matched;
    }

    public boolean remove(String driveId, Criteria condition) {
        Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(new ObjectId(driveId)), condition));
        boolean removed = mongo.remove(query, RecruitmentDrive.class).getDeletedCount() > 0;
        caches.clearClubDirectory();
        return removed;
    }

    /** {@code positions.$[p].<field>} for {@link #forPosition}. */
    public static String positionField(String field) {
        return "positions.$[p]." + field;
    }

    /** {@code positions.$[p].rounds.$[r].<field>} for {@link #forRound}. */
    public static String roundField(String field) {
        return "positions.$[p].rounds.$[r]." + field;
    }

    public static Update forPosition(Update update, String positionId) {
        return update.filterArray(Criteria.where("p._id").is(new ObjectId(positionId)));
    }

    public static Update forRound(Update update, String positionId, String roundId) {
        return forPosition(update, positionId).filterArray(Criteria.where("r._id").is(new ObjectId(roundId)));
    }

    /** Matches the drive while the role exists and also matches {@code condition} (on the role's own fields). */
    public static Criteria positionWhere(String positionId, Criteria condition) {
        Criteria element = Criteria.where("_id").is(new ObjectId(positionId));
        return Criteria.where("positions").elemMatch(condition == null ? element : new Criteria().andOperator(element, condition));
    }

    /** The document form of a sub-document (a new role or round), as the mapping stores it. */
    public Document toDocument(Object subDocument) {
        Document document = new Document();
        mongo.getConverter().write(subDocument, document);
        return document;
    }

    // ---------------------------------------------------------------- reads shared by the views

    /** Application counts per role and in total (RecruitmentService.applicationCounts). */
    public DriveCounts counts(String driveId) {
        return counts(List.of(driveId)).getOrDefault(driveId, emptyCounts());
    }

    /** The same for many drives in one aggregation. */
    public Map<String, DriveCounts> counts(Collection<String> driveIds) {
        List<ObjectId> ids = driveIds.stream().filter(Objects::nonNull).distinct().map(ObjectId::new).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("drive").in(ids)),
                Aggregation.group("drive", "position", "status").count().as("count"));
        Map<String, int[]> totals = new LinkedHashMap<>();
        Map<String, Map<String, int[]>> byPosition = new HashMap<>();
        for (Document row : mongo.aggregate(aggregation, "recruitmentapplications", Document.class).getMappedResults()) {
            Document key = (Document) row.get("_id");
            if (key.getString("status") == null) {
                continue;
            }
            String drive = key.getObjectId("drive").toHexString();
            Object position = key.get("position");
            String positionId = position instanceof ObjectId id ? id.toHexString() : String.valueOf(position);
            ApplicationStatus status = ApplicationStatus.valueOf(key.getString("status"));
            int count = ((Number) row.get("count")).intValue();
            add(totals.computeIfAbsent(drive, unused -> new int[5]), status, count);
            add(byPosition.computeIfAbsent(drive, unused -> new LinkedHashMap<>()).computeIfAbsent(positionId, unused -> new int[5]), status, count);
        }
        Map<String, DriveCounts> result = new HashMap<>();
        for (ObjectId id : ids) {
            String drive = id.toHexString();
            int[] total = totals.getOrDefault(drive, new int[5]);
            Map<String, Counts> positions = new LinkedHashMap<>();
            byPosition.getOrDefault(drive, Map.of()).forEach((position, bucket) -> positions.put(position, toCounts(bucket)));
            result.put(drive, new DriveCounts(total[0], total[1], total[2], total[3], total[4], positions));
        }
        return result;
    }

    public static Counts emptyPositionCounts() {
        return new Counts(0, 0, 0, 0, 0);
    }

    private static DriveCounts emptyCounts() {
        return new DriveCounts(0, 0, 0, 0, 0, Map.of());
    }

    private static void add(int[] bucket, ApplicationStatus status, int count) {
        if (status != ApplicationStatus.WITHDRAWN) {
            bucket[0] += count;
        }
        if (ACTIVE_APPLICATION.contains(status)) {
            bucket[1] += count;
        }
        if (status == ApplicationStatus.OFFERED) {
            bucket[2] += count;
        }
        if (status == ApplicationStatus.ACCEPTED) {
            bucket[3] += count;
        }
        if (status == ApplicationStatus.RESERVE) {
            bucket[4] += count;
        }
    }

    private static Counts toCounts(int[] bucket) {
        return new Counts(bucket[0], bucket[1], bucket[2], bucket[3], bucket[4]);
    }

    /** The venues of the drives' rounds with the given fields (populate "positions.rounds.venue"), in one query. */
    public Map<String, Map<String, Object>> venues(Collection<RecruitmentDrive> drives, String... fields) {
        Set<String> ids = new LinkedHashSet<>();
        drives.forEach(drive -> drive.getPositions().forEach(position -> position.getRounds().forEach(round -> {
            if (round.getVenue() != null) {
                ids.add(round.getVenue());
            }
        })));
        return ids.isEmpty() ? Map.of() : refs.load("venues", ids, fields);
    }
}
