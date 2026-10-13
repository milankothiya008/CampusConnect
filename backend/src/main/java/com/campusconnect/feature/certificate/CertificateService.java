package com.campusconnect.feature.certificate;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.event.Team;
import com.campusconnect.domain.event.TeamMember;
import com.campusconnect.domain.event.TeamMemberStatus;
import com.campusconnect.domain.event.TeamStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.result.Certificate;
import com.campusconnect.domain.result.CertificateKind;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.feature.certificate.CertificateDtos.CertificateFile;
import com.campusconnect.feature.certificate.CertificateDtos.CertificateView;
import com.campusconnect.feature.certificate.CertificateDtos.EventCertificates;
import com.campusconnect.feature.certificate.CertificateDtos.Verification;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Certificates, when the president switches them on for an event (services/CertificateService.js):
 * <ul>
 *   <li>PARTICIPATION — every student checked in at the event, once it has ended;</li>
 *   <li>MERIT — winners in the published final results (every member of a winning team).</li>
 * </ul>
 * They are issued on first request with a random code anyone can verify at /verify/&lt;code&gt;. Listing a student's
 * certificates works in a fixed number of queries however many events they attended.
 */
@Slf4j
@Service
public class CertificateService {

    /** No 0/O or 1/I: codes are read off paper. 32^10 possible codes, drawn with a CSPRNG, so they can't be guessed. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ISSUE_ATTEMPTS = 5;
    private static final List<String> ON_TEAM = List.of(TeamMemberStatus.LEADER.name(), TeamMemberStatus.ACCEPTED.name());
    private static final String[] EVENT_FIELDS = {"title", "club", "status", "startAt", "endAt", "certificatesEnabled"};

    private final MongoOperations mongo;
    private final Refs refs;
    private final NotificationService notifications;
    private final CertificatePdf pdf;

    public CertificateService(MongoOperations mongo, Refs refs, NotificationService notifications, CertificatePdf pdf) {
        this.mongo = mongo;
        this.refs = refs;
        this.notifications = notifications;
        this.pdf = pdf;
    }

    /** What a user can get for one event. */
    public record Entitlement(CertificateKind kind, String awardTitle, String teamName) {
    }

    static String newCode() {
        StringBuilder code = new StringBuilder("CERT-");
        for (int i = 0; i < 10; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    static boolean hasEnded(Event event, Instant now) {
        return event.getStatus() == EventStatus.COMPLETED
                || (event.getStatus() == EventStatus.PUBLISHED && event.getEndAt() != null && !event.getEndAt().isAfter(now));
    }

    private static ObjectId oid(String id) {
        return new ObjectId(id);
    }

    private static String link(String eventId) {
        return "/events/" + eventId + "#certificates";
    }

    private Event findEvent(String eventId) {
        Query query = Query.query(Criteria.where("_id").is(oid(eventId)));
        query.fields().include(EVENT_FIELDS);
        return mongo.findOne(query, Event.class);
    }

    // ---------------------------------------------------------------- what people are entitled to

    /** The facts entitlements are computed from, loaded for many events at once. */
    private record Facts(Set<String> attended, Map<String, List<ResultAward>> awards, Map<String, List<String>> teamNames) {
    }

    private Facts factsFor(String userId, Collection<String> eventIds) {
        List<ObjectId> events = eventIds.stream().map(ObjectId::new).toList();
        ObjectId user = oid(userId);

        Query registrations = Query.query(Criteria.where("event").in(events).and("user").is(user)
                .and("status").is(RegistrationStatus.REGISTERED.name()).and("checkedInAt").ne(null));
        registrations.fields().include("event");
        Set<String> attended = new HashSet<>();
        mongo.find(registrations, Document.class, "eventregistrations").forEach(row -> attended.add(row.getObjectId("event").toHexString()));

        Query results = Query.query(Criteria.where("event").in(events).and("status").is(ResultStatus.PUBLISHED.name()));
        results.fields().include("event").include("awards");
        Map<String, List<ResultAward>> awards = new HashMap<>();
        mongo.find(results, EventResult.class).forEach(result -> awards.put(result.getEvent(), result.getAwards()));

        Query teams = Query.query(Criteria.where("event").in(events).and("status").is(TeamStatus.ACTIVE.name())
                .and("members").elemMatch(Criteria.where("user").is(user).and("status").in(ON_TEAM)));
        teams.fields().include("event").include("name");
        teams.with(Sort.by("_id"));
        Map<String, List<String>> teamNames = new HashMap<>();
        mongo.find(teams, Team.class).forEach(team -> teamNames.computeIfAbsent(team.getEvent(), key -> new ArrayList<>()).add(team.getName()));
        return new Facts(attended, awards, teamNames);
    }

    /** What the user can get for this event (entitlementsFor): participation if checked in, merit for an award. */
    private static List<Entitlement> entitlements(Event event, String userId, Facts facts, Instant now) {
        List<Entitlement> list = new ArrayList<>();
        if (!event.isCertificatesEnabled() || !hasEnded(event, now)) {
            return list;
        }
        List<String> teams = facts.teamNames().getOrDefault(event.getId(), List.of());
        List<String> lowered = teams.stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();
        if (facts.attended().contains(event.getId())) {
            list.add(new Entitlement(CertificateKind.PARTICIPATION, null, teams.isEmpty() ? null : teams.get(0)));
        }
        facts.awards().getOrDefault(event.getId(), List.of()).stream()
                .filter(award -> userId.equals(award.getRecipientUser())
                        || (award.getTeamName() != null && !award.getTeamName().isEmpty() && lowered.contains(award.getTeamName().toLowerCase(Locale.ROOT))))
                .min(Comparator.comparingInt(award -> award.getPosition() == null || award.getPosition() == 0 ? 99 : award.getPosition()))
                .ifPresent(award -> list.add(new Entitlement(CertificateKind.MERIT, award.getTitle(),
                        award.getTeamName() == null || award.getTeamName().isEmpty() ? null : award.getTeamName())));
        return list;
    }

    /** Entitlements of one user for one event (for other features, e.g. a participation record). */
    public List<Entitlement> entitlementsFor(Event event, String userId) {
        return entitlements(event, userId, factsFor(userId, List.of(event.getId())), Instant.now());
    }

    // ---------------------------------------------------------------- issuing

    /** The user's certificates for these events, in their order, issuing (or refreshing) any they qualify for. */
    private List<CertificateView> issueFor(AuthUser actor, List<Event> events) {
        List<CertificateView> items = new ArrayList<>();
        if (events.isEmpty()) {
            return items;
        }
        Instant now = Instant.now();
        Facts facts = factsFor(actor.id(), events.stream().map(Event::getId).toList());
        Map<Event, List<Entitlement>> due = new LinkedHashMap<>();
        events.forEach(event -> {
            List<Entitlement> entitlements = entitlements(event, actor.id(), facts, now);
            if (!entitlements.isEmpty()) {
                due.put(event, entitlements);
            }
        });
        if (due.isEmpty()) {
            return items;
        }

        Set<String> clubIds = new LinkedHashSet<>();
        due.keySet().forEach(event -> clubIds.add(event.getClub()));
        Map<String, Map<String, Object>> clubs = refs.clubs(clubIds, "name");
        Map<String, Object> user = refs.user(actor.id(), "name");
        String recipientName = user == null || user.get("name") == null ? actor.name() : String.valueOf(user.get("name"));
        Query existingQuery = Query.query(Criteria.where("user").is(oid(actor.id()))
                .and("event").in(due.keySet().stream().map(event -> oid(event.getId())).toList()));
        Map<String, Certificate> existing = new HashMap<>();
        mongo.find(existingQuery, Certificate.class).forEach(certificate -> existing.put(certificate.getEvent() + "|" + certificate.getKind(), certificate));

        due.forEach((event, entitlements) -> {
            Map<String, Object> club = clubs.get(event.getClub());
            String clubName = club == null || club.get("name") == null ? null : String.valueOf(club.get("name"));
            for (Entitlement entitlement : entitlements) {
                Certificate certificate = existing.get(event.getId() + "|" + entitlement.kind());
                items.add(CertificateView.of(certificate != null ? refresh(certificate, entitlement) : create(event, clubName, actor.id(), recipientName, entitlement)));
            }
        });
        return items;
    }

    /** Keeps the wording current (e.g. results corrected) while the code stays the same. */
    private Certificate refresh(Certificate certificate, Entitlement entitlement) {
        if (Objects.equals(certificate.getAwardTitle(), entitlement.awardTitle()) && certificate.getRevokedAt() == null) {
            return certificate;
        }
        Instant now = Instant.now();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(certificate.getId()))),
                new Update().set("awardTitle", entitlement.awardTitle()).set("teamName", entitlement.teamName()).set("revokedAt", null).set("updatedAt", now),
                Certificate.class);
        certificate.setAwardTitle(entitlement.awardTitle());
        certificate.setTeamName(entitlement.teamName());
        certificate.setRevokedAt(null);
        certificate.setUpdatedAt(now);
        return certificate;
    }

    private Certificate create(Event event, String clubName, String userId, String recipientName, Entitlement entitlement) {
        for (int attempt = 0; attempt < ISSUE_ATTEMPTS; attempt++) {
            Certificate certificate = new Certificate();
            certificate.setCode(newCode());
            certificate.setEvent(event.getId());
            certificate.setClub(event.getClub());
            certificate.setUser(userId);
            certificate.setKind(entitlement.kind());
            certificate.setRecipientName(recipientName);
            certificate.setEventTitle(event.getTitle());
            certificate.setClubName(clubName);
            certificate.setEventStartAt(event.getStartAt());
            certificate.setEventEndAt(event.getEndAt());
            certificate.setAwardTitle(entitlement.awardTitle());
            certificate.setTeamName(entitlement.teamName());
            try {
                return mongo.insert(certificate);
            } catch (DuplicateKeyException collision) {
                // Either the same request raced us (same event, user and kind) or, very rarely, the code was taken.
                Certificate raced = mongo.findOne(Query.query(Criteria.where("event").is(oid(event.getId())).and("user").is(oid(userId))
                        .and("kind").is(entitlement.kind().name())), Certificate.class);
                if (raced != null) {
                    return raced;
                }
            }
        }
        throw AppException.of(500, "Couldn't issue the certificate, please try again", ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- the routes

    /** The viewer's certificates for one event (issued on the spot if they qualify). */
    public EventCertificates myCertificatesForEvent(AuthUser actor, String eventId) {
        Event event = findEvent(eventId);
        if (event == null) {
            throw AppException.notFound("Event not found");
        }
        List<CertificateView> items = issueFor(actor, List.of(event));
        return new EventCertificates(items.isEmpty() ? event.isCertificatesEnabled() : true, items);
    }

    /** Every certificate the student has, across events (issuing any they qualify for but haven't opened yet). */
    public List<CertificateView> listMine(AuthUser actor) {
        ObjectId user = oid(actor.id());
        Query attendedQuery = Query.query(Criteria.where("user").is(user).and("status").is(RegistrationStatus.REGISTERED.name()).and("checkedInAt").ne(null));
        attendedQuery.fields().include("event");
        Set<String> eventIds = new LinkedHashSet<>();
        mongo.find(attendedQuery, Document.class, "eventregistrations").forEach(row -> eventIds.add(row.getObjectId("event").toHexString()));

        Query teamQuery = Query.query(Criteria.where("status").is(TeamStatus.ACTIVE.name())
                .and("members").elemMatch(Criteria.where("user").is(user).and("status").in(ON_TEAM)));
        teamQuery.fields().include("event");
        List<ObjectId> teamEvents = mongo.find(teamQuery, Document.class, "teams").stream().map(row -> row.getObjectId("event")).toList();

        Query wonQuery = Query.query(Criteria.where("status").is(ResultStatus.PUBLISHED.name())
                .orOperator(Criteria.where("awards.recipientUser").is(user), Criteria.where("event").in(teamEvents)));
        wonQuery.fields().include("event");
        mongo.find(wonQuery, Document.class, "eventresults").forEach(row -> eventIds.add(row.getObjectId("event").toHexString()));
        if (eventIds.isEmpty()) {
            return new ArrayList<>();
        }

        Query eventsQuery = Query.query(Criteria.where("_id").in(eventIds.stream().map(ObjectId::new).toList()).and("certificatesEnabled").is(true))
                .with(Sort.by(Sort.Direction.DESC, "startAt"));
        eventsQuery.fields().include(EVENT_FIELDS);
        return issueFor(actor, mongo.find(eventsQuery, Event.class));
    }

    private Certificate loadByCode(String code) {
        String normalized = String.valueOf(code == null ? "" : code).strip().toUpperCase(Locale.ROOT);
        return mongo.findOne(Query.query(Criteria.where("code").is(normalized)), Certificate.class);
    }

    /** Public check of a certificate code. */
    public Object verify(String code) {
        Certificate certificate = loadByCode(code);
        if (certificate == null) {
            Map<String, Object> unknown = new LinkedHashMap<>();
            unknown.put("valid", false);
            return unknown;
        }
        return new Verification(certificate.getRevokedAt() == null, certificate.getRevokedAt() != null, certificate.getRecipientName(), CertificateView.of(certificate));
    }

    /** The PDF, for its owner only. */
    public CertificateFile certificateFile(AuthUser actor, String code) {
        Certificate certificate = loadByCode(code);
        if (certificate == null || !actor.id().equals(certificate.getUser())) {
            throw AppException.notFound("Certificate not found");
        }
        if (certificate.getRevokedAt() != null) {
            throw AppException.of(410, "This certificate has been withdrawn", ErrorCode.INVALID_STATE);
        }
        Map<String, Object> club = refs.club(certificate.getClub(), "president", "mentor");
        String president = club == null ? null : (String) club.get("president");
        String mentor = club == null ? null : (String) club.get("mentor");
        Map<String, Map<String, Object>> people = refs.users(Arrays.asList(president, mentor), "name");
        byte[] bytes = pdf.render(certificate, nameOf(people.get(president)), nameOf(people.get(mentor)));
        String filename = certificate.getEventTitle().replaceAll("[^\\w-]+", "_") + "_" + (certificate.getKind() == CertificateKind.MERIT ? "merit" : "participation") + "_certificate.pdf";
        return new CertificateFile(bytes, filename);
    }

    private static String nameOf(Map<String, Object> user) {
        return user == null || user.get("name") == null ? null : String.valueOf(user.get("name"));
    }

    // ---------------------------------------------------------------- announcements

    /** Tells checked-in students their participation certificate is ready (the event ended with certificates on). */
    public void announceCertificates(String eventId) {
        Event event = findEvent(eventId);
        if (event == null || !event.isCertificatesEnabled()) {
            return;
        }
        Query query = Query.query(Criteria.where("event").is(oid(event.getId())).and("status").is(RegistrationStatus.REGISTERED.name()).and("checkedInAt").ne(null));
        query.fields().include("user");
        List<String> attendees = mongo.find(query, Document.class, "eventregistrations").stream().map(row -> row.getObjectId("user").toHexString()).toList();
        notifications.notify(attendees, Notice.builder()
                .type(NotificationType.CERTIFICATE_READY)
                .title("Your certificate for " + event.getTitle() + " is ready")
                .message("Download your participation certificate from the event page.")
                .link(link(event.getId()))
                .email(true)
                .emailCategory(EmailCategory.EVENT_ACTIVITY)
                .build());
    }

    /** Tells winners their merit certificate is ready (when final results are published). */
    public void announceMerit(Event event, EventResult result) {
        if (!event.isCertificatesEnabled()) {
            return;
        }
        List<String> recipients = new ArrayList<>(result.getAwards().stream().map(ResultAward::getRecipientUser).filter(Objects::nonNull).toList());
        List<String> teamNames = result.getAwards().stream().map(ResultAward::getTeamName).filter(name -> name != null && !name.isEmpty())
                .map(name -> name.toLowerCase(Locale.ROOT)).toList();
        if (!teamNames.isEmpty()) {
            Query query = Query.query(Criteria.where("event").is(oid(event.getId())).and("status").is(TeamStatus.ACTIVE.name()));
            query.fields().include("name").include("members");
            for (Team team : mongo.find(query, Team.class)) {
                if (team.getName() != null && teamNames.contains(team.getName().toLowerCase(Locale.ROOT))) {
                    team.getMembers().stream().filter(member -> member.getStatus() == TeamMemberStatus.LEADER || member.getStatus() == TeamMemberStatus.ACCEPTED)
                            .map(TeamMember::getUser).forEach(recipients::add);
                }
            }
        }
        notifications.notify(recipients, Notice.builder()
                .type(NotificationType.CERTIFICATE_READY)
                .title("Your merit certificate for " + event.getTitle() + " is ready")
                .message("Congratulations! Download it from the event page.")
                .link(link(event.getId()))
                .email(true)
                .emailCategory(EmailCategory.EVENT_ACTIVITY)
                .build());
    }
}
