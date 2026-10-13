package com.campusconnect.feature.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.events.EventCertificatesDue;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.notification.Notification;
import com.campusconnect.domain.result.Certificate;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.feature.result.EventFixtures;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData.Venues;
import com.campusconnect.support.TestUser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Certificates beyond what the Node tests cover: the PDF itself, forged codes, ownership, withdrawal, re-issue, announcements. */
class CertificateIntegrationTest extends IntegrationTest {

    @Autowired
    ApplicationEventPublisher publisher;

    Club club;
    EventFixtures events;
    Event sprint;
    TestUser president;
    TestUser asha;
    TestUser ravi;

    @BeforeEach
    void completedSprint() {
        Venues venues = data.seedReferenceData();
        TestUser mentor = data.faculty("CE", "Dr Mentor");
        president = data.student(s -> s.name("Priya President"));
        asha = data.student(s -> s.name("Asha Patel"));
        ravi = data.student(s -> s.name("Ravi Shah"));
        club = data.activeClub("Coding Club", mentor, president);
        events = new EventFixtures(mongo);
        sprint = events.published(club, venues.auditorium(), 5, event -> {
            event.setTitle("Code Sprint");
            event.setCertificatesEnabled(true);
        });
        events.register(sprint, asha);
        events.register(sprint, ravi);
        events.checkIn(sprint, asha, ravi);
        events.finish(sprint, true);
        publishedResult("Winner", asha.id());
    }

    void publishedResult(String title, String winnerId) {
        ResultAward award = new ResultAward();
        award.setTitle(title);
        award.setPosition(1);
        award.setRecipientUser(winnerId);
        award.setRecipientName("Asha Patel");
        EventResult result = new EventResult();
        result.setEvent(sprint.getId());
        result.setClub(club.getId());
        result.setSummary("A close final.");
        result.setAwards(new ArrayList<>(List.of(award)));
        result.setStatus(ResultStatus.PUBLISHED);
        result.setPublishedAt(Instant.now());
        result.setCreatedBy(president.id());
        mongo.insert(result);
    }

    String certificates() {
        return "/api/events/" + sprint.getId() + "/certificates";
    }

    String meritCode(TestUser user) {
        Response res = api.as(user).get(certificates());
        List<Map<String, Object>> items = res.at("$.data.items");
        return items.stream().filter(item -> "MERIT".equals(item.get("kind"))).map(item -> (String) item.get("code")).findFirst().orElseThrow();
    }

    MockHttpServletResponse pdf(TestUser user, String code) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/certificates/" + code + "/pdf").header("Authorization", "Bearer " + user.token())).andReturn().getResponse();
    }

    @Test
    void theCertificatePdfIsAValidPdfWithTheRecipientsNameAndItsVerificationLink() throws Exception {
        String code = meritCode(asha);
        MockHttpServletResponse response = pdf(asha, code);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"Code_Sprint_merit_certificate.pdf\"");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        byte[] bytes = response.getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        PdfReader reader = new PdfReader(bytes);
        assertThat(reader.getNumberOfPages()).isEqualTo(1);
        assertThat(reader.getInfo().get("Title")).isEqualTo("Asha Patel — Code Sprint");
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("Asha Patel", "Certificate of Merit", "This is to certify that", "Coding Club", "Priya President", "Dr Mentor", code,
                "http://localhost:3000/verify/" + code);
        reader.close();

        // The participation certificate says so, and is named after it.
        String participation = ((List<String>) api.as(asha).get(certificates()).at("$.data.items[?(@.kind == 'PARTICIPATION')].code")).get(0);
        MockHttpServletResponse second = pdf(asha, participation);
        assertThat(second.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"Code_Sprint_participation_certificate.pdf\"");
        PdfReader participationReader = new PdfReader(second.getContentAsByteArray());
        assertThat(new PdfTextExtractor(participationReader).getTextFromPage(1)).contains("Certificate of Participation", "has successfully participated in Code Sprint");
        participationReader.close();
    }

    @Test
    void codesAreRandomAndUnguessableAndAForgedCodeNeverVerifies() {
        String code = meritCode(asha);
        assertThat(code).matches("^CERT-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$");
        assertThat(meritCode(asha)).as("asking again keeps the code").isEqualTo(code);

        // A well-formed code nobody was issued, and the real code with one character changed.
        char last = code.charAt(code.length() - 1);
        String tampered = code.substring(0, code.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThat((Map<String, Object>) api.get("/api/certificates/verify/" + tampered).at("$.data")).isEqualTo(Map.of("valid", false));
        assertThat((Map<String, Object>) api.get("/api/certificates/verify/CERT-ZZZZZZZZZZ").at("$.data")).isEqualTo(Map.of("valid", false));

        Response malformed = api.get("/api/certificates/verify/CERT-123");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.message()).isEqualTo("Invalid certificate ID");
        assertThat(malformed.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(api.get("/api/certificates/verify/CERT-ABCDEFGHJKL").status()).isEqualTo(400);

        // Codes are case-insensitive; verification is public (a bad token is ignored).
        Response lower = api.withToken("not-a-token").get("/api/certificates/verify/" + code.toLowerCase(Locale.ROOT));
        assertThat(lower.status()).isEqualTo(200);
        assertThat((Boolean) lower.at("$.data.valid")).isTrue();
        assertThat(lower.string("$.data.recipientName")).isEqualTo("Asha Patel");
    }

    @Test
    void onlyTheOwnerDownloadsThePdfAndAWithdrawnCertificateIsGone() throws Exception {
        String code = meritCode(asha);
        assertThat(pdf(ravi, code).getStatus()).isEqualTo(404);
        assertThat(api.get("/api/certificates/" + code + "/pdf").status()).isEqualTo(401);
        assertThat(api.as(asha).get("/api/certificates/CERT-12/pdf").status()).isEqualTo(400);

        mongo.updateFirst(Query.query(Criteria.where("code").is(code)), new Update().set("revokedAt", Instant.now()), Certificate.class);
        Response gone = api.as(asha).get("/api/certificates/" + code + "/pdf");
        assertThat(gone.status()).isEqualTo(410);
        assertThat(gone.message()).isEqualTo("This certificate has been withdrawn");
        assertThat(gone.errorCode()).isEqualTo("INVALID_STATE");
        Map<String, Object> check = api.get("/api/certificates/verify/" + code).at("$.data");
        assertThat(check).containsEntry("valid", false).containsEntry("revoked", true).containsEntry("code", code);
    }

    @Test
    void certificatesAreIssuedOnceKeepTheirCodeAndFollowCorrectedResults() throws Exception {
        List<Callable<Response>> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            requests.add(() -> api.as(asha).get(certificates()));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (Future<Response> future : pool.invokeAll(requests)) {
                assertThat(future.get().status()).isEqualTo(200);
            }
        }
        assertThat(mongo.count(Query.query(Criteria.where("user").is(new ObjectId(asha.id()))), Certificate.class)).isEqualTo(2);

        String code = meritCode(asha);
        mongo.updateFirst(Query.query(Criteria.where("event").is(new ObjectId(sprint.getId()))), new Update().set("awards.0.title", "Champion"), EventResult.class);
        Response corrected = api.as(asha).get(certificates());
        assertThat((List<String>) corrected.at("$.data.items[?(@.kind == 'MERIT')].awardTitle")).containsExactly("Champion");
        assertThat(meritCode(asha)).isEqualTo(code);

        List<Map<String, Object>> mine = api.as(asha).get("/api/certificates/mine").at("$.data");
        assertThat(mine).extracting(item -> item.get("kind")).containsExactly("PARTICIPATION", "MERIT");
        Response ravis = api.as(ravi).get(certificates());
        assertThat((Boolean) ravis.at("$.data.enabled")).isTrue();
        assertThat((List<String>) ravis.at("$.data.items[*].kind")).containsExactly("PARTICIPATION");
    }

    @Test
    void certificatesWaitUntilTheEventIsOverAndAreOffUnlessSwitchedOn() {
        events.update(sprint, new Update().set("status", "PUBLISHED").set("endAt", Instant.now().plusSeconds(3600)));
        Response running = api.as(asha).get(certificates());
        assertThat((Boolean) running.at("$.data.enabled")).isTrue();
        assertThat((List<?>) running.at("$.data.items")).isEmpty();

        events.update(sprint, new Update().set("status", "COMPLETED").set("certificatesEnabled", false));
        Response off = api.as(asha).get(certificates());
        assertThat((Boolean) off.at("$.data.enabled")).isFalse();
        assertThat((List<?>) off.at("$.data.items")).isEmpty();
        assertThat(api.as(asha).get("/api/events/" + new ObjectId() + "/certificates").status()).isEqualTo(404);
        assertThat(api.as(asha).get("/api/events/not-an-id/certificates").message()).isEqualTo("Invalid id");
    }

    @Test
    void anEventWhoseAttendanceIsFinalTellsTheCheckedInStudentsTheirCertificateIsReady() {
        TestUser absent = data.student(s -> s.name("Absent"));
        events.register(sprint, absent);

        publisher.publishEvent(new EventCertificatesDue(sprint.getId()));

        List<Notification> ready = mongo.find(Query.query(Criteria.where("type").is("CERTIFICATE_READY")), Notification.class);
        assertThat(ready).extracting(Notification::getUser).containsExactlyInAnyOrder(asha.id(), ravi.id());
        assertThat(ready.get(0).getTitle()).isEqualTo("Your certificate for Code Sprint is ready");
        assertThat(ready.get(0).getLink()).isEqualTo("/events/" + sprint.getId() + "#certificates");
        assertThat(data.lastMailTo(asha.email()).subject()).isEqualTo("Your certificate for Code Sprint is ready");
        assertThat(data.mailsTo(absent.email())).isEmpty();

        // Never throws, even for an unknown event or an invalid id.
        publisher.publishEvent(new EventCertificatesDue(new ObjectId().toHexString()));
        publisher.publishEvent(new EventCertificatesDue("not-an-id"));
        assertThat(mongo.count(Query.query(Criteria.where("type").is("CERTIFICATE_READY")), Notification.class)).isEqualTo(2);
    }
}
