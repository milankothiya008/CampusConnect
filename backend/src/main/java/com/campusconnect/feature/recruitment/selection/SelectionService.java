package com.campusconnect.feature.recruitment.selection;

import static com.campusconnect.feature.recruitment.drive.DrivePhases.ACTIVE_APPLICATION;
import static com.campusconnect.feature.recruitment.drive.DrivePhases.names;
import static com.campusconnect.feature.recruitment.drive.Drives.invalid;
import static com.campusconnect.feature.recruitment.drive.Drives.invalidState;
import static com.campusconnect.feature.recruitment.selection.RoundService.lastRound;
import static com.campusconnect.feature.recruitment.selection.RoundService.oid;

import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentRound;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.recruitment.RoundStatus;
import com.campusconnect.feature.recruitment.drive.DriveCompletion;
import com.campusconnect.feature.recruitment.drive.DrivePhases;
import com.campusconnect.feature.recruitment.drive.DriveService;
import com.campusconnect.feature.recruitment.drive.Drives;
import com.campusconnect.feature.recruitment.dto.DriveViews.DriveView;
import com.campusconnect.feature.recruitment.dto.SelectionViews.Selection;
import com.campusconnect.feature.recruitment.mail.RecruitmentMailer;
import com.campusconnect.feature.recruitment.support.Js;
import com.campusconnect.feature.recruitment.support.RecruitmentLocks;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Final selection of each role and the offers (RecruitmentRoundService.js finalizePosition, offerToReserve,
 * closeRecruitment): every remaining candidate gets an OFFER (accepted or declined by the deadline), goes on the
 * RESERVE list (offered a seat that frees up) or is NOT_SELECTED. Each candidate's decision is applied once, so a
 * repeated request never sends a second offer, and seats offered to the reserve list are counted under the role's
 * seat lock.
 */
@Service
public class SelectionService {

    private static final List<String> DECISIONS = List.of("OFFER", "RESERVE", "NOT_SELECTED");
    private static final String NOT_ON_RESERVE = "This candidate isn't on the reserve list any more";

    private final MongoOperations mongo;
    private final Drives drives;
    private final RoundService rounds;
    private final DriveService driveService;
    private final DriveCompletion completion;
    private final RecruitmentMailer mailer;
    private final RecruitmentLocks locks;

    public SelectionService(MongoOperations mongo, Drives drives, RoundService rounds, DriveService driveService, DriveCompletion completion, RecruitmentMailer mailer,
            RecruitmentLocks locks) {
        this.mongo = mongo;
        this.drives = drives;
        this.rounds = rounds;
        this.driveService = driveService;
        this.completion = completion;
        this.mailer = mailer;
        this.locks = locks;
    }

    private static Update offer(Instant now, Instant expiresAt) {
        return new Update().set("status", ApplicationStatus.OFFERED.name()).set("offeredAt", now).set("offerExpiresAt", expiresAt).set("decidedAt", now).set("updatedAt", now);
    }

    /**
     * Final selection for one role: a decision for every finalist, at most as many offers as openings.
     *
     * @param offerDays days students get to answer (1–14), or null to keep the role's
     */
    public Selection finalizePosition(AuthUser actor, String driveId, String positionId, List<Map<String, Object>> decisions, Object offerDays) {
        Drives.Running running = drives.running(actor, driveId, positionId);
        RecruitmentDrive drive = running.drive();
        RecruitmentPosition position = running.position();
        Club club = running.club();
        if (position.getFinalizedAt() != null) {
            throw invalidState("The final selection for this role is done");
        }
        RecruitmentRound last = lastRound(position);
        if (last != null && last.getStatus() != RoundStatus.RESULTS_PUBLISHED) {
            throw invalidState("Publish the results of \"" + last.getName() + "\" first");
        }
        int days = position.getOfferDays();
        if (offerDays != null) {
            double value = Js.number(offerDays);
            if (!Js.isInteger(value) || value < 1 || value > 14) {
                throw invalid("Give students 1 to 14 days to answer an offer");
            }
            days = (int) value;
        }
        List<RecruitmentApplication> candidates = rounds.activeCandidates(drive.getId(), position.getId());
        Map<String, Object> byId = new HashMap<>();
        if (decisions != null) {
            decisions.forEach(decision -> {
                if (decision != null) {
                    byId.put(decision.get("applicationId") == null ? "undefined" : Js.string(decision.get("applicationId")), decision.get("decision"));
                }
            });
        }
        long missing = candidates.stream().filter(candidate -> !(byId.get(candidate.getId()) instanceof String decision && DECISIONS.contains(decision))).count();
        if (missing > 0) {
            throw invalidState("Decide every finalist — " + missing + " still " + (missing == 1 ? "has" : "have") + " no decision");
        }
        long offerCount = candidates.stream().filter(candidate -> "OFFER".equals(byId.get(candidate.getId()))).count();
        Integer openings = position.getOpenings();
        if (openings != null && openings != 0 && offerCount > openings) {
            throw invalid(position.getTitle() + " has " + openings + " " + (openings == 1 ? "opening" : "openings")
                    + " — make at most that many offers and put the rest on the reserve list");
        }
        if (ClubRoles.VICE_PRESIDENT.equals(position.getRole()) && offerCount > 0 && viceTaken(club)) {
            throw invalidState("Your club already has a vice-president");
        }

        Instant now = DrivePhases.now();
        Instant expiresAt = now.plus(Duration.ofDays(days));
        List<String> offered = new ArrayList<>();
        List<String> reserve = new ArrayList<>();
        List<String> notSelected = new ArrayList<>();
        for (RecruitmentApplication candidate : candidates) {
            String decision = (String) byId.get(candidate.getId());
            Update update = switch (decision) {
                case "OFFER" -> offer(now, expiresAt);
                case "RESERVE" -> new Update().set("status", ApplicationStatus.RESERVE.name()).set("decidedAt", now).set("updatedAt", now);
                default -> new Update().set("status", ApplicationStatus.NOT_SELECTED.name()).set("decidedAt", now).set("updatedAt", now);
            };
            boolean applied = mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(candidate.getId())).and("status").in(names(ACTIVE_APPLICATION))), update,
                    RecruitmentApplication.class).getModifiedCount() > 0;
            if (applied) {
                (switch (decision) {
                    case "OFFER" -> offered;
                    case "RESERVE" -> reserve;
                    default -> notSelected;
                }).add(candidate.getApplicant());
            }
        }
        drives.update(drive.getId(), Drives.positionWhere(position.getId(), Criteria.where("finalizedAt").is(null)), Drives.forPosition(new Update()
                .set(Drives.positionField("finalizedAt"), now)
                .set(Drives.positionField("offerDays"), days), position.getId()));
        position.setOfferDays(days);
        mailer.sendOffers(drive, club, position, offered, expiresAt);
        mailer.sendReserve(drive, club, position, reserve);
        mailer.sendNotSelected(drive, club, notSelected, position.getTitle());
        rounds.audit(AuditAction.POSITION_FINALIZED, actor, drive, position, Map.of("offers", (int) offerCount,
                "reserve", (int) candidates.stream().filter(candidate -> "RESERVE".equals(byId.get(candidate.getId()))).count()));
        completion.completeIfDone(drive.getId());
        return rounds.getRounds(actor, drive.getId(), position.getId());
    }

    private boolean viceTaken(Club club) {
        return mongo.exists(Query.query(Criteria.where("club").is(oid(club.getId())).and("role").is(ClubRoles.VICE_PRESIDENT).and("status").is(MembershipStatus.APPROVED.name())),
                ClubMembership.class);
    }

    /** Offers a freed seat (declined or expired offer) to a candidate on the reserve list. */
    public Selection offerToReserve(AuthUser actor, String driveId, String positionId, String applicationId) {
        RecruitmentDrive drive = drives.load(driveId);
        Club club = drives.assertManager(actor, drive).clubDocument();
        RecruitmentPosition position = Drives.position(drive, positionId);
        if (drive.getStatus() != RecruitmentStatus.PUBLISHED || position.getFinalizedAt() == null) {
            throw invalidState("Offers to the reserve list come after the final selection");
        }
        Query reserveQuery = Query.query(Criteria.where("_id").is(oid(applicationId)).and("drive").is(oid(drive.getId())).and("position").is(oid(position.getId()))
                .and("status").is(ApplicationStatus.RESERVE.name()));
        RecruitmentApplication application = mongo.findOne(reserveQuery, RecruitmentApplication.class);
        if (application == null) {
            throw invalidState(NOT_ON_RESERVE);
        }
        Integer openings = position.getOpenings();
        Instant now = DrivePhases.now();
        Instant expiresAt = now.plus(Duration.ofDays(position.getOfferDays()));
        locks.withPositionLock(position.getId(), () -> {
            if (openings != null && openings != 0) {
                long taken = mongo.count(Query.query(Criteria.where("drive").is(oid(drive.getId())).and("position").is(oid(position.getId()))
                        .and("status").in(names(List.of(ApplicationStatus.ACCEPTED, ApplicationStatus.OFFERED)))), RecruitmentApplication.class);
                if (taken >= openings) {
                    throw invalidState("All " + openings + " " + position.getTitle() + " seats are taken or offered");
                }
            }
            if (mongo.updateFirst(reserveQuery, offer(now, expiresAt), RecruitmentApplication.class).getModifiedCount() == 0) {
                throw invalidState(NOT_ON_RESERVE);
            }
            return null;
        });
        mailer.sendOffers(drive, club, position, List.of(application.getApplicant()), expiresAt);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("applicationId", applicationId);
        details.put("fromReserve", true);
        rounds.audit(AuditAction.OFFER_MADE, actor, drive, position, details);
        return rounds.getRounds(actor, drive.getId(), position.getId());
    }

    /** President: end recruitment now. Anyone still waiting (rounds, reserve, unanswered offers) is thanked. */
    public DriveView closeRecruitment(AuthUser actor, String driveId) {
        RecruitmentDrive drive = drives.load(driveId);
        drives.assertManager(actor, drive);
        if (drive.getStatus() != RecruitmentStatus.PUBLISHED || DrivePhases.takingApplications(drive, Instant.now())) {
            throw invalidState("Recruitment can be completed once applications have closed");
        }
        completion.completeIfDone(drive.getId(), true, actor);
        return driveService.getDrive(actor, drive.getId());
    }
}
