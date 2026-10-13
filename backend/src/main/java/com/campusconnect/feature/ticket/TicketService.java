package com.campusconnect.feature.ticket;

import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.mail.EmailContent;
import com.campusconnect.core.mail.EmailContent.Detail;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.EmailRequest;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.tokens.TicketTokens;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.core.util.Updates;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.RegistrationStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Tickets (services/TicketService.js): every registration that holds a place carries a ticket code and a signed QR
 * token ({@link TicketTokens}). The ticket is shown in the app and e-mailed with the QR image; club officers scan or
 * type it at the door (CheckInService).
 */
@Slf4j
@Service
public class TicketService {

    static final int MAX_CODE_ATTEMPTS = 3;
    static final int APP_QR_WIDTH = 512;
    static final int EMAIL_QR_WIDTH = 360;

    private final MongoOperations mongo;
    private final TicketTokens tokens;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Refs refs;

    public TicketService(MongoOperations mongo, TicketTokens tokens, NotificationService notifications, AuditService audit, Refs refs) {
        this.mongo = mongo;
        this.tokens = tokens;
        this.notifications = notifications;
        this.audit = audit;
        this.refs = refs;
    }

    /** A fresh ticket: a new code, and no attendance yet (written whenever a registration (re)enters REGISTERED). */
    public record TicketFields(String ticketCode, Instant ticketIssuedAt) {

        public static TicketFields fresh() {
            return new TicketFields(TicketTokens.generateCode(), Instant.now());
        }

        public Update applyTo(Update update) {
            return update.set("ticketCode", ticketCode)
                    .set("ticketIssuedAt", ticketIssuedAt)
                    .set("checkedInAt", null)
                    .set("checkedInBy", null)
                    .set("checkInMethod", null)
                    .set("checkInNote", null);
        }

        public EventRegistration applyTo(EventRegistration registration) {
            registration.setTicketCode(ticketCode);
            registration.setTicketIssuedAt(ticketIssuedAt);
            registration.setCheckedInAt(null);
            registration.setCheckedInBy(null);
            registration.setCheckInMethod(null);
            registration.setCheckInNote(null);
            return registration;
        }
    }

    /**
     * Runs a registration write with fresh ticket fields, drawing a new code on the (very rare) duplicate-code error.
     * Any other duplicate error is rethrown, so callers keep their "already registered" handling.
     */
    public <T> T withTicketRetry(Function<TicketFields, T> write) {
        for (int attempt = 1; ; attempt++) {
            try {
                return write.apply(TicketFields.fresh());
            } catch (DuplicateKeyException e) {
                if (isTicketCodeClash(e) && attempt < MAX_CODE_ATTEMPTS) {
                    continue;
                }
                throw e;
            }
        }
    }

    public static boolean isTicketCodeClash(DuplicateKeyException e) {
        return String.valueOf(e.getMessage()).contains("ticketCode");
    }

    public String tokenFor(EventRegistration registration) {
        return tokens.createToken(registration.getId(), registration.getTicketCode());
    }

    /** Registrations made before tickets existed get a code the first time their ticket is opened. */
    public EventRegistration ensureTicket(EventRegistration registration) {
        if (registration.getStatus() != RegistrationStatus.REGISTERED || registration.getTicketCode() != null) {
            return registration;
        }
        EventRegistration updated = withTicketRetry(ticket -> mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(registration.getId())).and("status").is(RegistrationStatus.REGISTERED.name())
                        .and("ticketCode").exists(false)),
                Updates.touch(new Update().set("ticketCode", ticket.ticketCode()).set("ticketIssuedAt", ticket.ticketIssuedAt())),
                FindAndModifyOptions.options().returnNew(true),
                EventRegistration.class));
        if (updated != null) {
            audit.record(AuditAction.TICKET_ISSUED, registration.getUser(), "EventRegistration", registration.getId(),
                    Map.of("eventId", registration.getEvent(), "reason", "backfill"));
            return updated;
        }
        return mongo.findById(registration.getId(), EventRegistration.class);
    }

    /** The signed-in student's ticket for an event (only while they hold a place). */
    public Map<String, Object> getMyTicket(AuthUser actor, String eventId) {
        EventRegistration found = mongo.findOne(Query.query(Criteria.where("event").is(eventId).and("user").is(actor.id())
                .and("status").is(RegistrationStatus.REGISTERED)), EventRegistration.class);
        if (found == null) {
            throw AppException.notFound("You don't have a ticket for this event");
        }
        EventRegistration registration = ensureTicket(found);
        Event event = mongo.findById(eventId, Event.class);
        if (event == null) {
            throw AppException.notFound("Event not found");
        }
        Map<String, Object> venue = refs.one("venues", event.getVenue(), "name", "location");
        Map<String, Object> club = refs.club(event.getClub(), "name", "logo");
        Map<String, Object> team = found.getTeam() == null ? null : refs.one("teams", found.getTeam(), "name");
        String token = tokenFor(registration);

        Map<String, Object> eventView = new LinkedHashMap<>();
        eventView.put("_id", event.getId());
        eventView.put("title", event.getTitle());
        eventView.put("status", event.getStatus());
        eventView.put("startAt", event.getStartAt());
        eventView.put("endAt", event.getEndAt());
        eventView.put("when", whenLabel(event));
        eventView.put("venue", venue == null ? null : subset(venue, "name", "location"));
        eventView.put("club", club);
        eventView.put("poster", event.getPoster());
        eventView.put("participationMode", event.getParticipationMode());

        Map<String, Object> holder = new LinkedHashMap<>();
        holder.put("_id", actor.id());
        holder.put("name", actor.name());
        holder.put("email", actor.email());

        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("registrationId", registration.getId());
        ticket.put("ticketCode", registration.getTicketCode());
        ticket.put("issuedAt", registration.getTicketIssuedAt());
        ticket.put("checkedInAt", registration.getCheckedInAt());
        ticket.put("checkInMethod", registration.getCheckInMethod());
        ticket.put("token", token);
        ticket.put("qrDataUrl", QrImages.dataUrl(token, APP_QR_WIDTH));
        ticket.put("holder", holder);
        ticket.put("event", eventView);
        if (team == null) {
            ticket.put("team", null);
        } else {
            Map<String, Object> teamView = new LinkedHashMap<>();
            teamView.put("name", team.get("name"));
            teamView.put("role", registration.getTeamRole());
            ticket.put("team", teamView);
        }
        return ticket;
    }

    /**
     * The QR image linked from ticket e-mails. Stateless: a valid signature is all that is needed to draw it; whether
     * the ticket still counts is decided when it is scanned.
     */
    public byte[] renderTicketPng(String token) {
        if (tokens.read(token) == null) {
            throw AppException.notFound("Ticket not found");
        }
        return QrImages.png(token, EMAIL_QR_WIDTH);
    }

    static String whenLabel(Event event) {
        String start = CampusTime.formatDateKey(CampusTime.toDateKey(event.getEventDate()));
        if (event.getEndDate() != null) {
            return start + ", " + event.getStartTime() + " – " + CampusTime.formatDateKey(CampusTime.toDateKey(event.getEndDate())) + ", " + event.getEndTime();
        }
        return start + ", " + event.getStartTime() + "–" + event.getEndTime();
    }

    static String whereLabel(Map<String, Object> venue) {
        if (venue == null) {
            return "";
        }
        return Stream.of(venue.get("name"), venue.get("location"))
                .filter(value -> value != null && !String.valueOf(value).isEmpty())
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }

    private static String subject(String reason, Event event, Map<String, Object> team) {
        return switch (reason) {
            case "team" -> "Your ticket for " + event.getTitle() + (team != null ? " · team \"" + team.get("name") + "\"" : "");
            // Wording kept in step with the earlier promotion e-mail.
            case "promoted" -> "You're in! A spot opened up for " + event.getTitle();
            default -> "Your ticket for " + event.getTitle();
        };
    }

    private static List<String> paragraphs(String reason, Map<String, Object> team) {
        return switch (reason) {
            case "team" -> List.of("You're registered" + (team != null ? " with team \"" + team.get("name") + "\"" : "")
                    + ". Every team member has their own ticket — show the QR code below at the entrance, or read out your ticket code.");
            case "promoted" -> List.of(
                    "You've moved off the waitlist and are now registered. If you can no longer make it, cancel so the next person gets your seat.",
                    "Show the QR code below at the entrance, or read out your ticket code.");
            default -> List.of("You're registered. Show the QR code below at the entrance, or read out your ticket code.");
        };
    }

    /** E-mails the ticket (QR image + code). Never throws: registration must succeed even if mail fails. */
    public int sendTicketEmail(String registrationId, String reason) {
        try {
            EventRegistration registration = mongo.findById(registrationId, EventRegistration.class);
            if (registration == null || registration.getStatus() != RegistrationStatus.REGISTERED || registration.getTicketCode() == null) {
                return 0;
            }
            Event event = mongo.findById(registration.getEvent(), Event.class);
            if (event == null) {
                return 0;
            }
            Map<String, Object> venue = refs.one("venues", event.getVenue(), "name", "location");
            Map<String, Object> team = registration.getTeam() == null ? null : refs.one("teams", registration.getTeam(), "name");
            String token = tokenFor(registration);
            String subject = subject(reason, event, team);
            List<String> paragraphs = paragraphs(reason, team);
            String when = whenLabel(event);
            String where = whereLabel(venue);
            return notifications.emailUsers(List.of(registration.getUser()), EmailRequest.builder()
                    .category(EmailCategory.ACCOUNT)
                    .dedupeKey(user -> "ticket:" + registration.getId() + ":" + registration.getTicketCode())
                    .compose(user -> EmailContent.builder()
                            .subject(subject)
                            .heading(subject)
                            .paragraphs(paragraphs)
                            .details(List.of(
                                    new Detail("Event", event.getTitle()),
                                    new Detail("When", when),
                                    new Detail("Where", where),
                                    new Detail("Ticket holder", user.name()),
                                    new Detail("Team", team == null ? null : String.valueOf(team.get("name")))))
                            .code(registration.getTicketCode())
                            .qr(new EmailContent.Qr("/api/tickets/" + token + "/qr.png", "QR ticket " + registration.getTicketCode()))
                            .action(new EmailContent.Action("Open your ticket", "/events/" + event.getId() + "?ticket=1"))
                            .build())
                    .build());
        } catch (RuntimeException e) {
            log.error("Ticket email failed for registration {} ({}): {}", registrationId, reason, e.getMessage());
            return 0;
        }
    }

    private static Map<String, Object> subset(Map<String, Object> source, String... keys) {
        Map<String, Object> view = new LinkedHashMap<>();
        for (String key : keys) {
            view.put(key, source.get(key));
        }
        return view;
    }
}
