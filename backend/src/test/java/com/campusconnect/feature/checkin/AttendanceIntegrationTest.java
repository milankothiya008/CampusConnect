package com.campusconnect.feature.checkin;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.core.tokens.TicketTokens;
import com.campusconnect.domain.audit.AuditLog;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.feature.event.EventFlows;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Port of tests/integration/attendance.test.js ("tickets and check-in"); the president's dashboard statistics test
 * belongs to the dashboard feature. Adds two officers scanning one ticket at the same moment.
 */
class AttendanceIntegrationTest extends IntegrationTest {

    static final Pattern CODE = Pattern.compile("^CC-[A-HJ-NP-TV-Z2-9]{8}$");

    @Autowired
    TicketTokens ticketTokens;

    TestData.Venues venues;
    EventFlows flows;
    TestUser mentor;
    TestUser president;
    TestUser coordinator;
    TestUser marketer;
    TestUser treasurer;
    TestUser member;
    TestUser asha;
    TestUser bina;
    TestUser chirag;
    TestUser dev;
    Club club;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        mentor = data.faculty();
        president = named("President");
        coordinator = named("Coordinator");
        marketer = named("Marketer");
        treasurer = named("Treasurer");
        member = named("Plain Member");
        asha = named("Asha Patel");
        bina = named("Bina Shah");
        chirag = named("Chirag Rao");
        dev = named("Dev Nair");
        club = data.activeClub("Coding Club", mentor, president);
        data.addMembership(club, coordinator, "EVENT_COORDINATOR");
        data.addMembership(club, marketer, "MARKETING_COORDINATOR");
        data.addMembership(club, treasurer, "TREASURER");
        data.addMembership(club, member, "MEMBER");
    }

    TestUser named(String name) {
        return data.student(spec -> spec.name(name));
    }

    String publishEvent(Object... overrides) {
        return flows.published(president, mentor, club, venues.auditorium(), overrides);
    }

    String tokenOf(TestUser student, String eventId) {
        EventRegistration registration = flows.registrationOf(student, eventId);
        return ticketTokens.createToken(registration.getId(), registration.getTicketCode());
    }

    Response scan(TestUser actor, String eventId, Map<String, Object> body) {
        return api.as(actor).post("/api/events/" + eventId + "/check-in/scan", body);
    }

    @Nested
    class IssuingTickets {

        String eventId;

        @BeforeEach
        void hackNight() {
            eventId = publishEvent("title", "Hack Night", "maxParticipants", 2, "eventDate", TestData.futureDate(10));
        }

        @Test
        void registeringIssuesATicketAndEmailsItWithTheQrImageAndCode() {
            assertThat(flows.register(asha, eventId).status()).isEqualTo(201);
            EventRegistration registration = flows.registrationOf(asha, eventId);
            assertThat(registration.getTicketCode()).matches(CODE);
            assertThat(registration.getTicketIssuedAt()).isNotNull();
            assertThat(registration.getCheckedInAt()).isNull();

            DevInbox.Entry mail = data.mailsTo(asha.email()).stream().filter(item -> item.subject().equals("Your ticket for Hack Night")).findFirst().orElseThrow();
            assertThat(mail.text()).contains("Ticket code: " + registration.getTicketCode());
            String token = tokenOf(asha, eventId);
            assertThat(mail.html()).contains("/api/tickets/" + token + "/qr.png");
            assertThat(mail.html()).contains("Open your ticket");
            assertThat(mail.text()).contains("/events/" + eventId + "?ticket=1");
            // The plain confirmation is no longer sent as a second e-mail.
            assertThat(data.mailsTo(asha.email()).stream().filter(item -> item.subject().contains("registered for Hack Night"))).isEmpty();
            assertThat(flows.notified(asha, "REGISTRATION_CONFIRMED")).isTrue();
        }

        @Test
        void theStudentCanOpenTheirTicketInTheAppOthersAndTheWaitlistedCannot() {
            flows.register(asha, eventId);
            Response ticket = api.as(asha).get("/api/events/" + eventId + "/ticket");
            assertThat(ticket.status()).isEqualTo(200);
            EventRegistration registration = flows.registrationOf(asha, eventId);
            assertThat(ticket.string("$.data.ticketCode")).isEqualTo(registration.getTicketCode());
            assertThat(ticket.string("$.data.token")).isEqualTo(tokenOf(asha, eventId));
            assertThat(ticket.string("$.data.holder.name")).isEqualTo("Asha Patel");
            assertThat(ticket.string("$.data.event.title")).isEqualTo("Hack Night");
            assertThat(ticket.string("$.data.event.venue.name")).isEqualTo("Auditorium");
            assertThat(ticket.string("$.data.event.club.name")).isEqualTo("Coding Club");
            assertThat((Object) ticket.at("$.data.checkedInAt")).isNull();
            assertThat(ticket.string("$.data.qrDataUrl")).startsWith("data:image/png;base64,");

            assertThat(api.as(bina).get("/api/events/" + eventId + "/ticket").status()).isEqualTo(404);
        }

        @Test
        void theQrImageLinkWorksWithoutSigningInAndRejectsTamperedTokens() {
            flows.register(asha, eventId);
            String token = tokenOf(asha, eventId);
            Response image = api.get("/api/tickets/" + token + "/qr.png");
            assertThat(image.status()).isEqualTo(200);
            assertThat(String.join(",", image.header("Content-Type"))).contains("image/png");
            assertThat(String.join(",", image.header("Cache-Control"))).isEqualTo("public, max-age=604800");
            assertThat(image.body().substring(1, 4)).isEqualTo("PNG");

            String[] parts = token.split("\\.");
            assertThat(api.get("/api/tickets/" + parts[0] + "." + parts[1] + "." + parts[2].substring(0, 31) + "Z/qr.png").status()).isEqualTo(404);
            assertThat(api.get("/api/tickets/nonsense/qr.png").status()).isEqualTo(400);
        }

        @Test
        void aWaitlistedStudentHasNoTicketUntilASeatOpensUp() {
            flows.register(asha, eventId);
            flows.register(bina, eventId);
            assertThat((Boolean) flows.register(chirag, eventId).at("$.data.waitlisted")).isTrue();
            assertThat(flows.registrationOf(chirag, eventId).getTicketCode()).isNull();
            assertThat(api.as(chirag).get("/api/events/" + eventId + "/ticket").status()).isEqualTo(404);

            flows.cancel(bina, eventId);
            EventRegistration promoted = flows.registrationOf(chirag, eventId);
            assertThat(promoted.getStatus().name()).isEqualTo("REGISTERED");
            assertThat(promoted.getTicketCode()).matches(CODE);

            DevInbox.Entry mail = data.mailsTo(chirag.email()).stream().filter(item -> item.subject().equals("You're in! A spot opened up for Hack Night")).findFirst()
                    .orElseThrow();
            assertThat(mail.text()).contains("moved off the waitlist and are now registered");
            assertThat(mail.text()).contains("Ticket code: " + promoted.getTicketCode());
        }

        @Test
        void cancellingInvalidatesTheTicketRegisteringAgainIssuesANewOne() {
            flows.register(asha, eventId);
            String oldToken = tokenOf(asha, eventId);
            String oldCode = flows.registrationOf(asha, eventId).getTicketCode();
            flows.cancel(asha, eventId);
            assertThat(api.as(asha).get("/api/events/" + eventId + "/ticket").status()).isEqualTo(404);
            assertThat(flows.notifiedTitled(asha, "You cancelled your registration for Hack Night")).isTrue();

            assertThat((Boolean) flows.register(asha, eventId).at("$.data.waitlisted")).isFalse();
            EventRegistration fresh = flows.registrationOf(asha, eventId);
            assertThat(fresh.getTicketCode()).matches(CODE).isNotEqualTo(oldCode);
            assertThat(tokenOf(asha, eventId)).isNotEqualTo(oldToken);
        }

        @Test
        void registrationsFromBeforeTicketsExistedGetACodeWhenTheTicketIsFirstOpened() {
            flows.register(asha, eventId);
            mongo.updateFirst(Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("user").is(new ObjectId(asha.id()))),
                    new Update().unset("ticketCode").unset("ticketIssuedAt"), EventRegistration.class);
            Response ticket = api.as(asha).get("/api/events/" + eventId + "/ticket");
            assertThat(ticket.status()).isEqualTo(200);
            assertThat(ticket.string("$.data.ticketCode")).matches(CODE);
            String registrationId = flows.registrationOf(asha, eventId).getId();
            assertThat(mongo.exists(Query.query(Criteria.where("action").is("TICKET_ISSUED").and("targetId").is(new ObjectId(registrationId))), AuditLog.class)).isTrue();
        }
    }

    @Nested
    class CheckInAtTheDoor {

        String eventId;
        String otherEventId;

        @BeforeEach
        void bootcamp() {
            eventId = flows.published(president, mentor, club, venues.hall(), "title", "Web Dev Bootcamp", "maxParticipants", 50, "eventDate", TestData.futureDate(12));
            otherEventId = publishEvent("title", "Other Talk", "maxParticipants", 50, "eventDate", TestData.futureDate(14));
            for (TestUser student : List.of(asha, bina, chirag)) {
                flows.register(student, eventId);
            }
            flows.register(dev, otherEventId);
        }

        void open() {
            assertThat(api.as(president).post("/api/events/" + eventId + "/check-in/open").status()).isEqualTo(200);
        }

        /** Asha scanned in; Bina's old ticket replaced, then scanned in. */
        void ashaAndBinaCheckedIn() {
            open();
            scan(coordinator, eventId, map("token", tokenOf(asha, eventId)));
            flows.cancel(bina, eventId);
            flows.register(bina, eventId);
            scan(coordinator, eventId, map("token", tokenOf(bina, eventId)));
        }

        void everyoneCheckedIn() {
            ashaAndBinaCheckedIn();
            scan(coordinator, eventId, map("code", flows.registrationOf(chirag, eventId).getTicketCode()));
        }

        @Test
        void onlyThePresidentOpensCheckInAndNobodyCanScanBeforeItOpens() {
            Response early = scan(coordinator, eventId, map("token", tokenOf(asha, eventId)));
            assertThat(early.status()).isEqualTo(409);
            assertThat(early.message()).contains("Check-in is not open");

            assertThat(api.as(coordinator).post("/api/events/" + eventId + "/check-in/open").status()).isEqualTo(403);
            assertThat(api.as(mentor).post("/api/events/" + eventId + "/check-in/open").status()).isEqualTo(403);

            Response opened = api.as(president).post("/api/events/" + eventId + "/check-in/open");
            assertThat(opened.status()).isEqualTo(200);
            assertThat(opened.string("$.data.checkIn.status")).isEqualTo("OPEN");
            assertThat((Boolean) opened.at("$.data.viewer.canManageCheckIn")).isTrue();
            assertThat((Boolean) opened.at("$.data.viewer.canMarkAttendance")).isTrue();
            assertThat(api.as(president).post("/api/events/" + eventId + "/check-in/open").status()).isEqualTo(409);

            for (TestUser officer : List.of(coordinator, marketer, treasurer)) {
                assertThat(mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(officer.id())).and("type").is("CHECK_IN_OPEN")
                        .and("link").is("/events/" + eventId + "/check-in")), com.campusconnect.domain.notification.Notification.class)).as(officer.name()).isTrue();
            }
            assertThat(flows.notified(member, "CHECK_IN_OPEN")).isFalse();
        }

        @Test
        void officersScanAQrToCheckAStudentInPlainMembersMentorsAndOutsidersCannot() {
            open();
            String token = tokenOf(asha, eventId);
            for (TestUser outsider : List.of(member, mentor, dev)) {
                assertThat(scan(outsider, eventId, map("token", token)).status()).as(outsider.name()).isEqualTo(403);
            }

            Response res = scan(coordinator, eventId, map("token", token));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.result")).isEqualTo("CHECKED_IN");
            assertThat(res.string("$.data.message")).isEqualTo("Asha Patel checked in");
            assertThat((Integer) res.at("$.data.counts.attended")).isEqualTo(1);
            assertThat((Integer) res.at("$.data.counts.registered")).isEqualTo(3);
            assertThat(res.string("$.data.attendee.name")).isEqualTo("Asha Patel");
            assertThat(res.string("$.data.attendee.checkInMethod")).isEqualTo("QR");
            assertThat(res.string("$.data.attendee.checkedInBy")).isEqualTo("Coordinator");
            assertThat(flows.notified(asha, "ATTENDANCE_MARKED", "You're checked in to Web Dev Bootcamp")).isTrue();
            assertThat(mongo.exists(Query.query(Criteria.where("action").is("ATTENDANCE_MARKED").and("actor").is(new ObjectId(coordinator.id()))), AuditLog.class)).isTrue();

            Response again = scan(marketer, eventId, map("token", token));
            assertThat(again.string("$.data.result")).isEqualTo("ALREADY_CHECKED_IN");
            assertThat(again.string("$.data.message")).matches("^Already checked in at \\d\\d:\\d\\d by Coordinator$");
            assertThat((Integer) again.at("$.data.counts.attended")).isEqualTo(1);
        }

        @Test
        void theStudentsTicketAndTheParticipantListShowTheCheckIn() {
            open();
            scan(coordinator, eventId, map("token", tokenOf(asha, eventId)));
            Response ticket = api.as(asha).get("/api/events/" + eventId + "/ticket");
            assertThat((Object) ticket.at("$.data.checkedInAt")).isNotNull();
            assertThat(ticket.string("$.data.checkInMethod")).isEqualTo("QR");
            Response detail = api.as(asha).get("/api/events/" + eventId);
            assertThat(detail.string("$.data.viewer.registration.checkedInAt")).isNotBlank();
            assertThat(detail.string("$.data.viewer.registration.ticketCode")).matches(CODE);

            Response list = api.as(coordinator).get("/api/events/" + eventId + "/registrations");
            List<Map<String, Object>> row = list.at("$.data[?(@.user.name == 'Asha Patel')]");
            assertThat(row.get(0)).containsEntry("checkInMethod", "QR");
            assertThat(((Map<?, ?>) row.get(0).get("checkedInBy")).get("name")).isEqualTo("Coordinator");
            assertThat((Integer) list.at("$.meta.event.attendedCount")).isEqualTo(1);
            assertThat(list.string("$.meta.event.checkIn.status")).isEqualTo("OPEN");

            Response mine = api.as(asha).get("/api/registrations/me");
            assertThat(mine.string("$.data[0].ticketCode")).matches(CODE);
            assertThat(mine.string("$.data[0].checkedInAt")).isNotBlank();
        }

        @Test
        void badScansAreReportedNotThrownOtherEventCancelledReplacedAndForgedTickets() {
            open();
            scan(coordinator, eventId, map("token", tokenOf(asha, eventId)));
            Response wrong = scan(coordinator, eventId, map("token", tokenOf(dev, otherEventId)));
            assertThat(wrong.string("$.data.result")).isEqualTo("WRONG_EVENT");
            assertThat(wrong.string("$.data.message")).isEqualTo("This ticket is for \"Other Talk\"");

            String staleToken = tokenOf(bina, eventId);
            flows.cancel(bina, eventId);
            Response cancelled = scan(coordinator, eventId, map("token", staleToken));
            assertThat(cancelled.string("$.data.result")).isEqualTo("NOT_REGISTERED");
            assertThat(cancelled.string("$.data.message")).isEqualTo("Registration was cancelled — ticket no longer valid");

            flows.register(bina, eventId);
            Response replaced = scan(coordinator, eventId, map("token", staleToken));
            assertThat(replaced.string("$.data.result")).isEqualTo("INVALID_TICKET");
            assertThat(replaced.string("$.data.message")).contains("replaced");
            assertThat(scan(coordinator, eventId, map("token", tokenOf(bina, eventId))).string("$.data.result")).isEqualTo("CHECKED_IN");

            String[] parts = staleToken.split("\\.");
            Response forged = scan(coordinator, eventId, map("token", parts[0] + "." + parts[1] + "." + parts[2].substring(0, 31) + "Z"));
            assertThat(forged.string("$.data.result")).isEqualTo("INVALID_TICKET");
            assertThat((Object) forged.at("$.data.attendee")).isNull();
        }

        @Test
        void officersCanAlsoFindStudentsByNameOrEmailAndMarkThemPresentOrUndoIt() {
            ashaAndBinaCheckedIn();
            Response found = api.as(treasurer).get("/api/events/" + eventId + "/check-in/participants?search=chir");
            assertThat(found.status()).isEqualTo(200);
            assertThat((List<String>) found.at("$.data[*].name")).containsExactly("Chirag Rao");
            assertThat((Object) found.at("$.data[0].checkedInAt")).isNull();
            assertThat(found.string("$.data[0].ticketCode")).matches(CODE);
            String registrationId = found.string("$.data[0].registrationId");

            Response marked = api.as(marketer).post("/api/events/" + eventId + "/check-in/attendance/" + registrationId, map("note", "ID checked"));
            assertThat(marked.string("$.data.result")).isEqualTo("CHECKED_IN");
            assertThat(marked.string("$.data.attendee.name")).isEqualTo("Chirag Rao");
            assertThat(marked.string("$.data.attendee.checkInMethod")).isEqualTo("MANUAL");
            assertThat(marked.string("$.data.attendee.checkedInBy")).isEqualTo("Marketer");
            assertThat((Integer) marked.at("$.data.counts.attended")).isEqualTo(3);

            Response undone = api.as(marketer).delete("/api/events/" + eventId + "/check-in/attendance/" + registrationId);
            assertThat(undone.string("$.data.result")).isEqualTo("UNMARKED");
            assertThat((Integer) undone.at("$.data.counts.attended")).isEqualTo(2);
            assertThat(api.as(marketer).delete("/api/events/" + eventId + "/check-in/attendance/" + registrationId).status()).isEqualTo(409);

            Response all = api.as(treasurer).get("/api/events/" + eventId + "/check-in/participants");
            assertThat((List<String>) all.at("$.data[*].name")).containsExactly("Asha Patel", "Bina Shah", "Chirag Rao");
        }

        @Test
        void aTypedTicketCodeWorksTooHoweverItIsWritten() {
            ashaAndBinaCheckedIn();
            String ticketCode = flows.registrationOf(chirag, eventId).getTicketCode();
            Response typed = scan(coordinator, eventId, map("code", " " + ticketCode.toLowerCase().replaceFirst("-", " ") + " "));
            assertThat(typed.string("$.data.result")).isEqualTo("CHECKED_IN");
            assertThat(typed.string("$.data.attendee.name")).isEqualTo("Chirag Rao");
            assertThat(typed.string("$.data.attendee.checkInMethod")).isEqualTo("MANUAL");
            Response unknown = scan(coordinator, eventId, map("code", "CC-ZZZZZZZZ"));
            assertThat(unknown.string("$.data.result")).isEqualTo("NOT_FOUND");
            assertThat(unknown.string("$.data.message")).isEqualTo("No ticket with code CC-ZZZZZZZZ");
            assertThat(scan(coordinator, eventId, map("code", "nope")).string("$.data.result")).isEqualTo("NOT_FOUND");
            assertThat(scan(coordinator, eventId, map()).status()).isEqualTo(400);
        }

        @Test
        void theScannerPageDataListsCountsAndRecentCheckIns() {
            everyoneCheckedIn();
            Response status = api.as(marketer).get("/api/events/" + eventId + "/check-in");
            assertThat(status.status()).isEqualTo(200);
            assertThat(status.string("$.data.checkIn.status")).isEqualTo("OPEN");
            assertThat((Integer) status.at("$.data.counts.attended")).isEqualTo(3);
            assertThat((Integer) status.at("$.data.counts.registered")).isEqualTo(3);
            assertThat((Boolean) status.at("$.data.canManage")).isFalse();
            assertThat((List<String>) status.at("$.data.recent[*].name")).containsExactly("Chirag Rao", "Bina Shah", "Asha Patel");
            assertThat((Boolean) api.as(president).get("/api/events/" + eventId + "/check-in").at("$.data.canManage")).isTrue();
            assertThat(api.as(member).get("/api/events/" + eventId + "/check-in").status()).isEqualTo(403);
        }

        @Test
        void closingStopsScanningThePresidentCanReopenCompletingTheEventClosesItForGood() {
            everyoneCheckedIn();
            assertThat(api.as(coordinator).post("/api/events/" + eventId + "/check-in/close").status()).isEqualTo(403);
            Response closed = api.as(president).post("/api/events/" + eventId + "/check-in/close");
            assertThat(closed.string("$.data.checkIn.status")).isEqualTo("CLOSED");

            Response late = scan(coordinator, eventId, map("token", tokenOf(asha, eventId)));
            assertThat(late.status()).isEqualTo(409);
            String ashaRegistration = flows.registrationOf(asha, eventId).getId();
            assertThat(api.as(marketer).delete("/api/events/" + eventId + "/check-in/attendance/" + ashaRegistration).status()).isEqualTo(409);

            assertThat(api.as(president).post("/api/events/" + eventId + "/check-in/open").string("$.data.checkIn.status")).isEqualTo("OPEN");

            flows.times(eventId, Duration.ofDays(-1), Duration.ofHours(-2), Duration.ofHours(-1), Duration.ofHours(1));
            Response completed = api.as(president).post("/api/events/" + eventId + "/complete");
            assertThat(completed.string("$.data.status")).isEqualTo("COMPLETED");
            assertThat(completed.string("$.data.checkIn.status")).isEqualTo("CLOSED");
            assertThat(api.as(president).post("/api/events/" + eventId + "/check-in/open").status()).isEqualTo(409);
        }

        @Test
        void twoOfficersScanningTheSameTicketAtOnceCheckItInExactlyOnce() throws Exception {
            open();
            String token = tokenOf(asha, eventId);
            List<Callable<Response>> scans = new ArrayList<>();
            for (TestUser officer : List.of(coordinator, marketer, treasurer, president)) {
                scans.add(() -> scan(officer, eventId, map("token", token)));
            }
            List<String> results = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(scans.size())) {
                for (Future<Response> result : pool.invokeAll(scans)) {
                    results.add(result.get().string("$.data.result"));
                }
            }
            assertThat(results).containsOnlyOnce("CHECKED_IN");
            assertThat(results.stream().filter("ALREADY_CHECKED_IN"::equals)).hasSize(3);
            assertThat(mongo.count(Query.query(Criteria.where("action").is("ATTENDANCE_MARKED")), AuditLog.class)).isEqualTo(1);
            assertThat(mongo.count(Query.query(Criteria.where("user").is(new ObjectId(asha.id())).and("type").is("ATTENDANCE_MARKED")),
                    com.campusconnect.domain.notification.Notification.class)).isEqualTo(1);
        }
    }

    @Nested
    class TeamEvents {

        @Test
        void everyMemberOfARegisteredTeamGetsTheirOwnTicketAndChecksInIndividually() {
            String eventId = flows.published(president, mentor, club, venues.hall(), "title", "Hack Relay", "participationMode", "TEAM", "minTeamSize", 2, "maxTeamSize", 3,
                    "maxParticipants", 1, "eventDate", TestData.futureDate(16));
            TestUser leader = named("Lena Leader");
            TestUser mate = named("Manav Mate");
            TestUser lateLeader = named("Late Leader");
            TestUser lateMate = named("Late Mate");

            Response created = flows.register(leader, eventId, map("teamName", "Byte Busters", "invitees", List.of(mate.id())));
            assertThat(created.status()).isEqualTo(201);
            api.as(mate).post("/api/events/" + eventId + "/teams/" + created.string("$.data.team._id") + "/accept");
            EventRegistration leaderRegistration = flows.registrationOf(leader, eventId);
            EventRegistration mateRegistration = flows.registrationOf(mate, eventId);
            assertThat(leaderRegistration.getTicketCode()).matches(CODE);
            assertThat(mateRegistration.getTicketCode()).matches(CODE).isNotEqualTo(leaderRegistration.getTicketCode());

            assertThat(data.mailsTo(leader.email()).stream().anyMatch(mail -> mail.subject().equals("Your ticket for Hack Relay · team \"Byte Busters\""))).isTrue();
            assertThat(data.mailsTo(mate.email()).stream().anyMatch(mail -> mail.subject().equals("Your ticket for Hack Relay · team \"Byte Busters\""))).isTrue();

            // A waitlisted team has no tickets until it moves in; then everyone gets one.
            Response late = flows.register(lateLeader, eventId, map("teamName", "Null Pointers", "invitees", List.of(lateMate.id())));
            assertThat((Boolean) late.at("$.data.waitlisted")).isTrue();
            api.as(lateMate).post("/api/events/" + eventId + "/teams/" + late.string("$.data.team._id") + "/accept");
            assertThat(flows.registrationOf(lateMate, eventId).getTicketCode()).isNull();
            flows.cancel(leader, eventId);
            assertThat(flows.registrationOf(lateLeader, eventId).getTicketCode()).matches(CODE);
            assertThat(flows.registrationOf(lateMate, eventId).getTicketCode()).matches(CODE);

            api.as(president).post("/api/events/" + eventId + "/check-in/open");
            Response first = scan(coordinator, eventId, map("token", tokenOf(lateLeader, eventId)));
            assertThat(first.string("$.data.result")).isEqualTo("CHECKED_IN");
            assertThat(first.string("$.data.attendee.team")).isEqualTo("Null Pointers");
            assertThat(first.string("$.data.attendee.teamRole")).isEqualTo("LEADER");
            assertThat((Integer) first.at("$.data.counts.attended")).isEqualTo(1);
            assertThat((Integer) first.at("$.data.counts.registered")).isEqualTo(2);
            Response second = scan(coordinator, eventId, map("token", tokenOf(lateMate, eventId)));
            assertThat(second.string("$.data.result")).isEqualTo("CHECKED_IN");
            assertThat(second.string("$.data.attendee.team")).isEqualTo("Null Pointers");
            assertThat(second.string("$.data.attendee.teamRole")).isEqualTo("MEMBER");
            assertThat((Integer) second.at("$.data.counts.attended")).isEqualTo(2);
        }
    }
}
